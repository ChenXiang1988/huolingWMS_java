package com.huoling.wms.site.domain;

import java.time.Instant;

/**
 * 单仓端变更申请的本地记录（权威表在 Hub 的 {@code hub_product_change_request}）。
 *
 * <p>状态取值与 Hub 同口径：{@code PENDING}、{@code APPROVED}、{@code REJECTED}（21 E-16）；
 * 审核结果由 Hub 的下行事件回写，{@code lastEventId} 用来去重重复投递。
 *
 * <p>{@code uploadStatus} 是上行进度，与业务状态分开：上行失败不影响本仓看到「已受理、待审核」，
 * 由投递器按退避重试（不把网络失败伪装成业务失败）。
 */
public record SiteChangeRequest(
        Long id,
        String requestNo,
        String acceptNo,
        Long siteId,
        String archiveId,
        long archiveVersion,
        Long ownerId,
        String changeField,
        String targetValue,
        String reason,
        String status,
        String reviewReason,
        Long archiveReviewedVersion,
        String lastEventId,
        String uploadStatus,
        int uploadAttempts,
        String lastError,
        Instant nextRetryAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String UPLOAD_PENDING = "PENDING";

    /** 新受理的申请：业务状态待审核，上行进度待上行。 */
    public static SiteChangeRequest accepted(String requestNo, String acceptNo, Long siteId,
                                             String archiveId, long archiveVersion, Long ownerId,
                                             String changeField, String targetValue, String reason,
                                             Instant now) {
        return new SiteChangeRequest(null, requestNo, acceptNo, siteId, archiveId, archiveVersion,
            ownerId, changeField, targetValue, reason, STATUS_PENDING, null, null, null,
            UPLOAD_PENDING, 0, null, null, now, now);
    }
}
