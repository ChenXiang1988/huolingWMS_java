package com.huoling.wms.masterdata.owner.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.huoling.wms.masterdata.acceptance.application.AcceptanceService;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 货主档案受理结果查询（API-027）。
 *
 * <p>只认 API-023 至 API-026 的受理号，并按会话货主域收窄（{@link OwnerAcceptanceQueries}）：
 * 域外与不存在同一响应（{@code NOT_FOUND_OWNER}），不泄露他人受理单是否存在。
 *
 * <p>六十秒兜底（契约 1.3「恢复」、API-027 验收用例）：受理单仍在处理中且已超过轮询上限时，
 * 不无休止地回「处理中」，而是返回可重试的 {@code DEPENDENCY_UNAVAILABLE} 并带重试间隔；
 * 调用方保留受理号，后台补偿扫描会把陈旧的受理单收尾成 {@code FAILED}，
 * 之后同一个受理号查到确定结论。这里不伪造成功，也不返回空结果冒充「已完成」。
 */
@Service
public class OwnerAcceptanceQueryService {

    private static final Logger log = LoggerFactory.getLogger(OwnerAcceptanceQueryService.class);

    /** 契约 1.3 的轮询上限（秒）：与 `12a` 的 `UI-F02` 对齐。 */
    static final long DEFAULT_PROCESSING_TIMEOUT_SECONDS = 60L;

    /** 超时兜底给出的重试间隔（毫秒）。 */
    private static final long RETRY_AFTER_MS = 1000L;

    private final AcceptanceService acceptances;
    private final long processingTimeoutSeconds;

    public OwnerAcceptanceQueryService(
            AcceptanceService acceptances,
            @Value("${wms.owner.acceptance.processing-timeout-seconds:60}") long processingTimeoutSeconds) {
        this.acceptances = acceptances;
        this.processingTimeoutSeconds = processingTimeoutSeconds <= 0
            ? DEFAULT_PROCESSING_TIMEOUT_SECONDS : processingTimeoutSeconds;
    }

    /**
     * 查询一次受理结果。
     *
     * @throws OwnerContractException 受理号不存在或不在数据域内时 {@code NOT_FOUND_OWNER}；
     *                               处理中超过轮询上限时 {@code DEPENDENCY_UNAVAILABLE}（可重试）
     */
    public OwnerAcceptanceView find(String acceptNo, OwnerScope scope) {
        AcceptanceService.View view = acceptances.find(OwnerAcceptanceQueries.of(scope), acceptNo);
        if ("PROCESSING".equals(view.acceptStatus()) && stale(view)) {
            log.warn("货主档案受理单超过 {} 秒仍在处理中 acceptNo={}", processingTimeoutSeconds,
                view.acceptNo());
            throw new OwnerContractException(OwnerErrorCodes.DEPENDENCY_UNAVAILABLE,
                "受理结果暂不可用，请保留受理号稍后重新查询", RETRY_AFTER_MS);
        }
        return new OwnerAcceptanceView(view.acceptStatus(), view.result(), view.errorCode(),
            view.message());
    }

    /** 处理中且距最后一次写入已超过轮询上限。 */
    private boolean stale(AcceptanceService.View view) {
        Instant updatedAt = view.dataTimestamp();
        if (updatedAt == null) {
            return false;
        }
        return Duration.between(updatedAt, Instant.now()).getSeconds() > processingTimeoutSeconds;
    }

    /**
     * API-027 响应：受理状态、结果摘要、失败错误码与说明。
     *
     * <p>不带 {@code dataTimestamp}：货主档案读权威库，成功响应 MUST NOT 伪造降级标识（契约 1.1）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OwnerAcceptanceView(String acceptStatus, JsonNode result, String errorCode,
                                      String errorMessage) {
    }
}
