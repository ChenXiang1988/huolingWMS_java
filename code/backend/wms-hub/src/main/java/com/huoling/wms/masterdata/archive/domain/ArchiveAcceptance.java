package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.masterdata.domain.SkuRuleCodes;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;

import java.time.Instant;

/**
 * 写接口受理记录（契约「写接口统一响应」）。
 *
 * <p>一条记录只对应一次首次受理：{@code scopeKey} + {@code apiCode} + {@code idempotencyKey} 唯一，
 * 重复提交回放 {@code acceptNo}，同键异体在幂等入口就被拒绝，不会走到这里。
 *
 * <p>{@code statusSnapshot} 是受理时的业务状态快照，写接口响应只回它；
 * 执行结果放在 {@code resultJson}，失败放 {@code errorCode}，二者都不会出现在写接口响应里。
 */
public record ArchiveAcceptance(
        Long id,
        String acceptNo,
        String apiCode,
        String scopeKey,
        long accountId,
        String idempotencyKey,
        String statusSnapshot,
        AcceptanceStatus status,
        String resultJson,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    /** 新建一条处理中的受理记录。 */
    public static ArchiveAcceptance processing(String acceptNo, String apiCode, String scopeKey,
                                               long accountId, String idempotencyKey,
                                               String statusSnapshot, Instant now) {
        if (acceptNo == null || acceptNo.isBlank()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "缺少受理号");
        }
        if (statusSnapshot == null || statusSnapshot.isBlank()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "缺少受理时的业务状态快照");
        }
        return new ArchiveAcceptance(null, acceptNo, apiCode, scopeKey, accountId, idempotencyKey,
            statusSnapshot, AcceptanceStatus.PROCESSING, null, null, null, now, now);
    }

    /** 执行成功：写入结果摘要，清空错误列。 */
    public ArchiveAcceptance succeeded(String resultJson, Instant now) {
        return new ArchiveAcceptance(id, acceptNo, apiCode, scopeKey, accountId, idempotencyKey,
            statusSnapshot, AcceptanceStatus.SUCCEEDED, resultJson, null, null, createdAt, now);
    }

    /** 执行失败：写入契约错误码与说明，结果摘要留空。 */
    public ArchiveAcceptance failed(String code, String message, Instant now) {
        return new ArchiveAcceptance(id, acceptNo, apiCode, scopeKey, accountId, idempotencyKey,
            statusSnapshot, AcceptanceStatus.FAILED, null, code, message, createdAt, now);
    }
}
