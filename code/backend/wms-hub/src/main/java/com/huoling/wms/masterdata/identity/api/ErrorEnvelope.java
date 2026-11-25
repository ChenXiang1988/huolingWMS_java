package com.huoling.wms.masterdata.identity.api;

import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;

import java.util.UUID;

/**
 * 统一响应包里 {@code code}、{@code retryable}、{@code traceId} 的推导规则（契约「响应包」：
 * 非零 {@code code} 取错误码常量的数字段）。控制器出口与过滤器出口共用一份，避免两处各写一套映射。
 */
final class ErrorEnvelope {

    private ErrorEnvelope() {
    }

    /** 业务码形如 AUTH_1001，响应体的 code 取末段数字，与契约错误码表一一对应。 */
    static int wireCode(String code) {
        int index = code.lastIndexOf('_');
        return Integer.parseInt(code.substring(index + 1));
    }

    static boolean retryable(String code) {
        return IdentityErrorCodes.COMMON_UNAVAILABLE.equals(code)
            || IdentityErrorCodes.COMMON_RATE_LIMITED.equals(code)
            || IdentityErrorCodes.AUTH_TOKEN_INVALID.equals(code);
    }

    static String traceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
