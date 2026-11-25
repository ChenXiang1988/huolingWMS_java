package com.huoling.wms.masterdata.archive.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveEventPayload;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxRepository;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.archive.domain.SkuReviewRecord;
import com.huoling.wms.masterdata.archive.domain.SkuReviewRepository;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营审核应用服务（API-008 单条、API-009 批量）。
 *
 * <p>审核通过在同一本地事务内完成四件事：档案状态与版本、审核留痕、本地消息表落库；
 * 任一步失败整体回滚，不留半成品（design「事务边界与并发」、22 T-05）。
 *
 * <p>批量逐条独立事务：单条冲突不影响其他条目，结果与输入条数一致（specs product-archive-review）。
 */
@Service
public class SkuReviewService {

    /** 批量单次上限（契约 API-009）。 */
    public static final int BATCH_MAX = 100;

    private final SkuArchiveRepository archiveRepository;
    private final SkuReviewRepository reviewRepository;
    private final ArchiveOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SkuReviewService(SkuArchiveRepository archiveRepository,
                           SkuReviewRepository reviewRepository,
                           ArchiveOutboxRepository outboxRepository,
                           ObjectMapper objectMapper,
                           TransactionTemplate transactionTemplate) {
        this.archiveRepository = archiveRepository;
        this.reviewRepository = reviewRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /** API-008：单条审核，全量数据域（运营端）。 */
    @Transactional
    public ReviewView review(String archiveNo, SkuArchiveAction action, String reason, long operatorId) {
        return doReview(archiveNo, action, reason, operatorId);
    }

    /**
     * API-009：批量处理（通过、驳回或停用），逐条独立事务，返回与输入条数一致的结果明细。
     *
     * <p>三种动作都逐条独立事务：单条冲突不影响其他条目（契约 API-009「并发冲突」）。
     */
    public BatchView batch(List<String> archiveNos, SkuArchiveAction action, String reason,
                          long operatorId) {
        if (archiveNos == null || archiveNos.isEmpty()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "条目编号数组不能为空");
        }
        if (archiveNos.size() > BATCH_MAX) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "单次批量上限 " + BATCH_MAX + " 条");
        }
        if (action != SkuArchiveAction.APPROVE && action != SkuArchiveAction.REJECT
            && action != SkuArchiveAction.DISABLE) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "批量动作只接受通过、驳回或停用");
        }
        List<BatchItem> items = new ArrayList<>(archiveNos.size());
        for (String archiveNo : archiveNos) {
            try {
                ReviewView view = transactionTemplate.execute(
                    status -> doBatchItem(archiveNo, action, reason, operatorId));
                items.add(BatchItem.succeeded(archiveNo, view));
            } catch (ArchiveException e) {
                items.add(BatchItem.failed(archiveNo, e.code(), e.getMessage()));
            } catch (SkuRuleViolation e) {
                items.add(BatchItem.failed(archiveNo, e.code(), e.getMessage()));
            }
        }
        long succeeded = items.stream().filter(BatchItem::success).count();
        return new BatchView(items.size(), succeeded, items.size() - succeeded, items);
    }

    /** 批量条目按动作分发：停用走停用规则，通过与驳回走审核规则，两者共用单条事务边界。 */
    private ReviewView doBatchItem(String archiveNo, SkuArchiveAction action, String reason,
                                   long operatorId) {
        return action == SkuArchiveAction.DISABLE
            ? doDisable(archiveNo, reason, operatorId)
            : doReview(archiveNo, action, reason, operatorId);
    }

    /**
     * 批量停用单条：状态机守卫加乐观锁，原因必填由 Kernel 的停用规则判定。
     *
     * <p>不写审核留痕：{@code hub_sku_review} 的动作闭集只有通过与驳回，
     * 停用不是审核结论，写进去会撞数据库的动作约束（本次不改历史迁移脚本）。
     * 停用本身的状态与版本已落在档案主表，受理结果摘要与下行事件按档案版本可追溯。
     */
    private ReviewView doDisable(String archiveNo, String reason, long operatorId) {
        SkuArchive archive = archiveRepository.findByArchiveNo(archiveNo)
            .orElseThrow(() -> new ArchiveException(ArchiveErrorCodes.NOT_FOUND_ARCHIVE, "档案不存在"));
        long loadedVersion = archive.version();
        // 期望版本取读取到的当前版本：批量请求体按契约不带 version，乐观锁由 update 的条件兜底。
        archive.disable(loadedVersion, reason, Instant.now());
        if (!archiveRepository.update(archive, loadedVersion, operatorId)) {
            throw new ArchiveException(ArchiveErrorCodes.CONFLICT_VERSION,
                "档案已被修改，请刷新后按当前状态操作");
        }
        return new ReviewView(archive.status(), archive.version());
    }

    private ReviewView doReview(String archiveNo, SkuArchiveAction action, String reason,
                                long operatorId) {
        if (action != SkuArchiveAction.APPROVE && action != SkuArchiveAction.REJECT) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "审核动作只接受通过或驳回");
        }
        SkuArchive archive = archiveRepository.findByArchiveNo(archiveNo)
            .orElseThrow(() -> new ArchiveException(ArchiveErrorCodes.NOT_FOUND_ARCHIVE, "档案不存在"));
        long loadedVersion = archive.version();
        SkuArchiveStatus from = archive.status();
        Instant now = Instant.now();
        if (action == SkuArchiveAction.APPROVE) {
            archive.approve(loadedVersion, now);
        } else {
            archive.reject(reason, now);
        }
        if (!archiveRepository.update(archive, loadedVersion, operatorId)) {
            throw new ArchiveException(ArchiveErrorCodes.CONFLICT_VERSION,
                "档案已被修改，请刷新后按当前状态操作");
        }
        reviewRepository.insert(new SkuReviewRecord(null, archive.id(), archive.archiveNo(),
            archive.ownerId(), action, from, archive.status(), reason, archive.version(),
            operatorId, now));
        if (action == SkuArchiveAction.APPROVE) {
            writeOutbox(archive, ArchiveOutboxMessage.TYPE_ARCHIVE_ACTIVATED, now);
        }
        return new ReviewView(archive.status(), archive.version());
    }

    /** 审核通过写下行消息：事件标识为档案编号加版本号，摘要含版本、数据时间戳与变更字段。 */
    private void writeOutbox(SkuArchive archive, String eventType, Instant now) {
        ArchiveEventPayload payload = ArchiveEventPayload.of(archive, List.of("status"));
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ArchiveException(ArchiveErrorCodes.INTERNAL_ERROR, "下行摘要序列化失败");
        }
        outboxRepository.insert(new ArchiveOutboxMessage(null,
            ArchiveOutboxMessage.eventIdOf(archive.archiveNo(), archive.version()), eventType,
            archive.archiveNo(), archive.version(), archive.ownerId(), json, now));
    }

    /**
     * API-008 的受理结果摘要：审核后的状态与版本号。
     *
     * <p>写接口响应只回受理号与受理时状态快照，审核结果由 API-016 按受理号查询，
     * 因此这里不携带受理号、也不进入写接口响应体。
     */
    public record ReviewView(SkuArchiveStatus status, long version) {
    }

    /** 批量结果：总数、成功数、失败数与逐条明细。 */
    public record BatchView(int total, long succeeded, long failed, List<BatchItem> items) {
    }

    /**
     * 批量明细：字段名与契约「字段结构与枚举·{@code result.items}」逐字一致。
     *
     * <p>条目只有契约列出的五个字段：{@code archiveId}、{@code success}、{@code status}、
     * {@code errorCode}、{@code message}。档案编号与错误码的 JSON 名由 {@link JsonProperty} 给出，
     * 不把内部命名（{@code archiveNo}、{@code code}）漏到受理结果摘要里。
     */
    public record BatchItem(
            @JsonProperty("archiveId") String archiveNo,
            boolean success,
            SkuArchiveStatus status,
            @JsonProperty("errorCode") String code,
            String message) {

        static BatchItem succeeded(String archiveNo, ReviewView view) {
            return new BatchItem(archiveNo, true, view.status(), null, null);
        }

        static BatchItem failed(String archiveNo, String code, String message) {
            return new BatchItem(archiveNo, false, null, code, message);
        }
    }
}
