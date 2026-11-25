package com.huoling.wms.site.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import com.huoling.wms.site.domain.SiteAcceptance;
import com.huoling.wms.site.domain.SiteAcceptanceRepository;
import com.huoling.wms.site.domain.SiteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.Function;

/**
 * 单仓端写接口受理（契约「写接口统一响应」、API-011 与 API-017）。
 *
 * <p>顺序固定：先落受理记录（{@code PROCESSING}），再执行；执行失败也保留受理号并写错误码，
 * 调用方按 API-017 查询就能拿到结论，不会出现「提交了却查不到」。
 *
 * <p>幂等：唯一键（本仓、接口、幂等键）保证同一键只产生一条受理记录。同键同指纹回放首次受理号，
 * 同键异体返回 {@code IDEMPOTENT_KEY_CONFLICT}；占位与执行之间的并发窗口返回
 * {@code IDEMPOTENT_IN_PROGRESS} 并带 {@code retryAfterMs}。
 */
@Service
public class SiteAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(SiteAcceptanceService.class);
    private static final int KEY_MAX_LENGTH = 64;

    private final SiteAcceptanceRepository acceptances;
    private final SiteNumbers numbers;
    private final ObjectMapper objectMapper;

    public SiteAcceptanceService(SiteAcceptanceRepository acceptances, SiteNumbers numbers,
                                 ObjectMapper objectMapper) {
        this.acceptances = acceptances;
        this.numbers = numbers;
        this.objectMapper = objectMapper;
    }

    /**
     * 受理一次写请求。
     *
     * @param siteId         本仓标识，来自令牌
     * @param statusSnapshot 受理时的业务状态快照
     * @param action         业务动作，返回值进受理结果摘要
     */
    public Ticket submit(Long siteId, String apiCode, String idempotencyKey, String requestTarget,
                         String requestBody, String statusSnapshot,
                         Function<String, Object> action) {
        String key = requireKey(idempotencyKey);
        String fingerprint = SiteFingerprints.of(apiCode, requestTarget, requestBody);

        SiteAcceptance existing = acceptances.findByIdempotency(siteId, apiCode, key);
        if (existing != null) {
            if (!fingerprint.equals(existing.fingerprint())) {
                throw new SiteException(ArchiveContractCodes.IDEMPOTENT_KEY_CONFLICT,
                    "同一 Idempotency-Key 已受理过，本次请求体与首次不同");
            }
            return new Ticket(existing.acceptNo(), existing.statusSnapshot());
        }

        SiteAcceptance acceptance = SiteAcceptance.processing(numbers.nextAcceptNo(), siteId,
            apiCode, key, fingerprint, statusSnapshot, Instant.now());
        try {
            acceptances.insert(acceptance);
        } catch (DataIntegrityViolationException e) {
            // 并发同键：唯一索引只放行一条，落后的请求回到首次受理记录上回放。
            SiteAcceptance winner = acceptances.findByIdempotency(siteId, apiCode, key);
            if (winner == null) {
                throw new SiteException(ArchiveContractCodes.INTERNAL_ERROR, "受理记录写入失败，请稍后重试");
            }
            if (!fingerprint.equals(winner.fingerprint())) {
                throw new SiteException(ArchiveContractCodes.IDEMPOTENT_KEY_CONFLICT,
                    "同一 Idempotency-Key 已受理过，本次请求体与首次不同");
            }
            return new Ticket(winner.acceptNo(), winner.statusSnapshot());
        }

        execute(acceptance.acceptNo(), action);
        return new Ticket(acceptance.acceptNo(), acceptance.statusSnapshot());
    }

    /**
     * 受理查询（API-017）。
     *
     * @throws SiteException 受理号不存在或不属于本仓为 {@code NOT_FOUND_ARCHIVE}
     */
    public View find(Long siteId, String acceptNo) {
        if (acceptNo == null || acceptNo.isBlank()) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID, "缺少受理号 acceptNo");
        }
        SiteAcceptance acceptance = acceptances.findByAcceptNo(siteId, acceptNo);
        if (acceptance == null) {
            // 跨仓与不存在同一响应：不泄露他人受理单是否存在。
            throw new SiteException(ArchiveContractCodes.NOT_FOUND_ARCHIVE, "受理单不存在");
        }
        return new View(acceptance.acceptNo(), acceptance.acceptStatus().name(),
            read(acceptance.resultJson()), acceptance.errorCode(), acceptance.errorMessage(),
            acceptance.updatedAt());
    }

    private void execute(String acceptNo, Function<String, Object> action) {
        try {
            Object result = action.apply(acceptNo);
            acceptances.markSucceeded(acceptNo, write(result), Instant.now());
        } catch (SiteException e) {
            acceptances.markFailed(acceptNo, e.code(), e.getMessage(), Instant.now());
        } catch (SkuRuleViolation e) {
            acceptances.markFailed(acceptNo, e.code(), e.getMessage(), Instant.now());
        } catch (RuntimeException e) {
            log.warn("受理单 {} 执行失败", acceptNo, e);
            acceptances.markFailed(acceptNo, ArchiveContractCodes.INTERNAL_ERROR,
                "执行失败，请带追踪编号报障", Instant.now());
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID, "缺少幂等键 Idempotency-Key");
        }
        if (key.length() > KEY_MAX_LENGTH) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "幂等键 Idempotency-Key 长度上限 " + KEY_MAX_LENGTH);
        }
        return key;
    }

    private String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new SiteException(ArchiveContractCodes.INTERNAL_ERROR, "受理结果序列化失败");
        }
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("受理结果解析失败，按空结果返回：{}", e.getMessage());
            return null;
        }
    }

    /** 写接口响应体：受理号与受理时状态快照（契约「写接口统一响应」）。 */
    public record Ticket(String acceptNo, String status) {
    }

    /** 受理查询响应体：受理状态、结果摘要、失败错误码与数据时间戳。 */
    public record View(String acceptNo, String acceptStatus, JsonNode result, String errorCode,
                       String message, Instant dataTimestamp) {
    }
}
