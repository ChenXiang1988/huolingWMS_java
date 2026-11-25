package com.huoling.wms.site.domain;

import java.time.Instant;

/**
 * 单仓端受理记录（契约「写接口统一响应」的单仓版本）。
 *
 * <p>先落受理再执行：即使执行失败，受理号也已经存在，调用方按 API-017 查询就能拿到错误码，
 * 不会出现「提交了但查不到」的空档。
 */
public record SiteAcceptance(
        Long id,
        String acceptNo,
        Long siteId,
        String apiCode,
        String idempotencyKey,
        String fingerprint,
        String statusSnapshot,
        AcceptanceState acceptStatus,
        String resultJson,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static SiteAcceptance processing(String acceptNo, Long siteId, String apiCode,
                                            String idempotencyKey, String fingerprint,
                                            String statusSnapshot, Instant now) {
        return new SiteAcceptance(null, acceptNo, siteId, apiCode, idempotencyKey, fingerprint,
            statusSnapshot, AcceptanceState.PROCESSING, null, null, null, now, now);
    }

    public SiteAcceptance succeeded(String resultJson, Instant now) {
        return new SiteAcceptance(id, acceptNo, siteId, apiCode, idempotencyKey, fingerprint,
            statusSnapshot, AcceptanceState.SUCCEEDED, resultJson, null, null, createdAt, now);
    }

    public SiteAcceptance failed(String code, String message, Instant now) {
        return new SiteAcceptance(id, acceptNo, siteId, apiCode, idempotencyKey, fingerprint,
            statusSnapshot, AcceptanceState.FAILED, null, code, message, createdAt, now);
    }
}
