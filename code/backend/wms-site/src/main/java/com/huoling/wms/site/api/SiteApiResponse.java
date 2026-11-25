package com.huoling.wms.site.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * 单仓端响应包：与商品档案契约「通用约定·错误响应结构」逐字一致。
 *
 * <p>四个必填字段 {@code code}、{@code message}、{@code traceId}、{@code retryable} 永远出现；
 * 限流与处理中必须带 {@code retryAfterMs}，降级查询带 {@code dataTimestamp}。
 * 空值不输出，避免成功响应带上无意义字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SiteApiResponse<T>(String code, String message, T data, String traceId, boolean retryable,
                                 Long retryAfterMs, Instant dataTimestamp) {

    public static final String OK = "0";

    public static <T> SiteApiResponse<T> ok(T data) {
        return new SiteApiResponse<>(OK, "ok", data, newTraceId(), false, null, null);
    }

    public static <T> SiteApiResponse<T> failure(String code, String message, boolean retryable) {
        return new SiteApiResponse<>(code, message, null, newTraceId(), retryable, null, null);
    }

    public static <T> SiteApiResponse<T> retryableFailure(String code, String message,
                                                          long retryAfterMs) {
        return new SiteApiResponse<>(code, message, null, newTraceId(), true, retryAfterMs, null);
    }

    private static String newTraceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
