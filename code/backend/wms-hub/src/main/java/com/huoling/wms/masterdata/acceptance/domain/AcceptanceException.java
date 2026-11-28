package com.huoling.wms.masterdata.acceptance.domain;

import com.huoling.wms.masterdata.domain.RuleViolation;

/**
 * 受理与幂等设施的异常：只带契约错误码与说明，HTTP 状态与 {@code retryable} 由接口层的映射表给出。
 *
 * <p>为什么不复用各业务的异常类型：受理机制被商品档案与货主档案共用，
 * 若它引用 {@code ArchiveException}，机制就反向依赖了业务包；反过来各业务也不该被迫继承机制的异常。
 * 接口层的异常映射同时认这一种与各业务自己的类型，响应形态完全一致。
 */
public class AcceptanceException extends RuleViolation {

    private final String code;
    private final long retryAfterMs;

    public AcceptanceException(String code, String message) {
        this(code, message, 0L);
    }

    public AcceptanceException(String code, String message, long retryAfterMs) {
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
