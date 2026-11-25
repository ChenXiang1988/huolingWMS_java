package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;

/**
 * 超过频率上限（契约 COMMON_9002）。带 Retry-After 建议等待秒数，由异常处理器写进响应头。
 */
public class RateLimitExceededException extends IdentityException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds, String message) {
        super(IdentityErrorCodes.COMMON_RATE_LIMITED, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
