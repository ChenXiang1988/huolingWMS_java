package com.huoling.wms.masterdata.identity.api;

/**
 * 统一响应包，与契约章一致：code / message / data / traceId / retryable。
 */
public record ApiResponse<T>(int code, String message, T data, String traceId, boolean retryable) {

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(0, "ok", data, traceId, false);
    }

    public static <T> ApiResponse<T> fail(int code, String message, String traceId, boolean retryable) {
        return new ApiResponse<>(code, message, null, traceId, retryable);
    }
}
