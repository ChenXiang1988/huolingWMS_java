package com.huoling.wms.masterdata.acceptance.application;

/**
 * 一次受理的要素（契约「幂等约定」）：作用域、接口号、幂等键、指纹来源、受理时状态快照、操作账号，
 * 以及可选的目标货主。
 *
 * <p>{@code subjectOwnerId} 是本次上移新增的可空字段：改档、停用、启用写目标货主，
 * 建档写空（受理时档案还不存在），商品档案侧恒为空——它落到
 * {@code hub_archive_acceptance.subject_owner_id}，是受理查询按数据域收窄的唯一依据。
 */
public record AcceptanceCommand(
        String scopeKey,
        String apiCode,
        String idempotencyKey,
        String requestTarget,
        String requestBody,
        String statusSnapshot,
        long accountId,
        Long subjectOwnerId) {

    /** 不带目标货主的受理要素：商品档案侧与货主档案建档使用。 */
    public static AcceptanceCommand of(String scopeKey, String apiCode, String idempotencyKey,
                                       String requestTarget, String requestBody,
                                       String statusSnapshot, long accountId) {
        return new AcceptanceCommand(scopeKey, apiCode, idempotencyKey, requestTarget, requestBody,
            statusSnapshot, accountId, null);
    }

    /** 带目标货主的受理要素：货主档案的改档、停用、启用使用。 */
    public static AcceptanceCommand ofOwner(String scopeKey, String apiCode, String idempotencyKey,
                                            String requestTarget, String requestBody,
                                            String statusSnapshot, long accountId,
                                            Long subjectOwnerId) {
        return new AcceptanceCommand(scopeKey, apiCode, idempotencyKey, requestTarget, requestBody,
            statusSnapshot, accountId, subjectOwnerId);
    }
}
