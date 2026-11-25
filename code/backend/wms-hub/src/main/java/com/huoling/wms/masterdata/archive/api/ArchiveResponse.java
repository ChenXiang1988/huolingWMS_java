package com.huoling.wms.masterdata.archive.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * 商品档案接口响应体：{@code code}、{@code message}、{@code data}、{@code traceId}、{@code retryable}
 * 五个必填字段与契约「通用约定·错误响应结构」一致，另有 {@code retryAfterMs} 与 {@code dataTimestamp}
 * 两个可带字段：限流与处理中必须带 {@code retryAfterMs}，降级查询带 {@code dataTimestamp}。
 *
 * <p>{@code code} 取契约错误码表里的符号码（如 {@code RULE_DUPLICATE_CODE}），成功为 {@code 0}。
 * 身份模块的响应包用数字码，两套包按端并存，收敛口径待契约方确认（已登记为契约缺口）。
 *
 * <p>{@code retryAfterMs} 与 {@code dataTimestamp} 为 {@code null} 时不输出，避免成功响应带上无意义字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArchiveResponse<T>(String code, String message, T data, String traceId, boolean retryable,
                                 Long retryAfterMs, Instant dataTimestamp) {

    public static final String OK = "0";

    public static <T> ArchiveResponse<T> ok(T data) {
        return new ArchiveResponse<>(OK, "ok", data, newTraceId(), false, null, null);
    }

    public static <T> ArchiveResponse<T> failure(String code, String message, boolean retryable) {
        return new ArchiveResponse<>(code, message, null, newTraceId(), retryable, null, null);
    }

    /** 可重试失败的响应体：限流与处理中带上重试间隔（契约「错误响应结构」）。 */
    public static <T> ArchiveResponse<T> retryableFailure(String code, String message,
                                                          long retryAfterMs) {
        return new ArchiveResponse<>(code, message, null, newTraceId(), true, retryAfterMs, null);
    }

    private static String newTraceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
