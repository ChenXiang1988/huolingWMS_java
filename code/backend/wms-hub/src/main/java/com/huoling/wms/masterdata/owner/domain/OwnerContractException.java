package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.masterdata.domain.RuleViolation;

/**
 * 货主档案接口的业务异常：携带契约错误码与可选的建议重试间隔，不带 HTTP 语义。
 *
 * <p>继承 {@link RuleViolation} 是为了让共用受理机制按错误码把业务失败落成受理记录，
 * 与商品档案的 {@code ArchiveException} 同一做法；HTTP 状态与 {@code retryable}
 * 由接口层的映射表给出。
 */
public class OwnerContractException extends RuleViolation {

    private final String code;
    private final long retryAfterMs;

    public OwnerContractException(String code, String message) {
        this(code, message, 0L);
    }

    public OwnerContractException(String code, String message, long retryAfterMs) {
        super(message);
        this.code = code;
        this.retryAfterMs = Math.max(0L, retryAfterMs);
    }

    @Override
    public String code() {
        return code;
    }

    /** 限流与处理中场景给出的建议等待毫秒数；0 表示不提供。 */
    public long retryAfterMs() {
        return retryAfterMs;
    }
}
