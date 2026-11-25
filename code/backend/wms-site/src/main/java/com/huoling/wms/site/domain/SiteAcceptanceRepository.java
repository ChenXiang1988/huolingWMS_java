package com.huoling.wms.site.domain;

import java.time.Instant;

/** 单仓受理记录仓储：唯一键（本仓、接口、幂等键）保证同一键只产生一条受理记录。 */
public interface SiteAcceptanceRepository {

    /** 按（本仓、接口、幂等键）取首次受理记录；不存在返回 {@code null}。 */
    SiteAcceptance findByIdempotency(Long siteId, String apiCode, String idempotencyKey);

    /** 按受理号取；不属于本仓的受理号返回 {@code null}（越权与不存在同一响应）。 */
    SiteAcceptance findByAcceptNo(Long siteId, String acceptNo);

    void insert(SiteAcceptance acceptance);

    void markSucceeded(String acceptNo, String resultJson, Instant now);

    void markFailed(String acceptNo, String errorCode, String errorMessage, Instant now);
}
