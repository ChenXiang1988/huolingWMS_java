package com.huoling.wms.masterdata.acceptance.domain;

import java.time.Instant;

/**
 * 写接口受理记录（契约「写接口统一响应」）。
 *
 * <p>一条记录只对应一次首次受理：{@code scopeKey} + {@code apiCode} + {@code idempotencyKey} 唯一，
 * 重复提交回放 {@code acceptNo}，同键异体在幂等入口就被拒绝，不会走到这里。
 *
 * <p>{@code statusSnapshot} 是受理时的业务状态快照，写接口响应只回它；
 * 执行结果放在 {@code resultJson}，失败放 {@code errorCode}，二者都不会出现在写接口响应里。
 *
 * <p>{@code subjectOwnerId} 是受理单的目标货主：改档、停用、启用写目标货主，建档为空
 * （受理时档案还不存在），商品档案侧恒为空。它是受理查询按数据域收窄的唯一依据
 * ——{@code scopeKey} 对运营端恒为 {@code HUB}，无法按货主区分。
 */
public record Acceptance(
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
        Long subjectOwnerId,
        Instant createdAt,
        Instant updatedAt) {

    /** 新建一条处理中的受理记录。 */
    public static Acceptance processing(String acceptNo, String apiCode, String scopeKey,
                                        long accountId, String idempotencyKey,
                                        String statusSnapshot, Long subjectOwnerId, Instant now) {
        if (acceptNo == null || acceptNo.isBlank()) {
            throw new AcceptanceException(AcceptanceErrorCodes.PARAM_INVALID, "缺少受理号");
        }
        if (statusSnapshot == null || statusSnapshot.isBlank()) {
            throw new AcceptanceException(AcceptanceErrorCodes.PARAM_INVALID,
                "缺少受理时的业务状态快照");
        }
        return new Acceptance(null, acceptNo, apiCode, scopeKey, accountId, idempotencyKey,
            statusSnapshot, AcceptanceStatus.PROCESSING, null, null, null, subjectOwnerId, now, now);
    }

    /** 执行成功：写入结果摘要，清空错误列。 */
    public Acceptance succeeded(String resultJson, Instant now) {
        return new Acceptance(id, acceptNo, apiCode, scopeKey, accountId, idempotencyKey,
            statusSnapshot, AcceptanceStatus.SUCCEEDED, resultJson, null, null, subjectOwnerId,
            createdAt, now);
    }

    /** 执行失败：写入契约错误码与说明，结果摘要留空。 */
    public Acceptance failed(String code, String message, Instant now) {
        return new Acceptance(id, acceptNo, apiCode, scopeKey, accountId, idempotencyKey,
            statusSnapshot, AcceptanceStatus.FAILED, null, code, message, subjectOwnerId,
            createdAt, now);
    }
}
