package com.huoling.wms.masterdata.archive.domain;

/**
 * 商品档案业务异常：只带契约错误码与说明，HTTP 状态与 {@code retryable} 由接口层的映射表给出。
 */
public class ArchiveException extends RuntimeException {

    private final String code;
    private final long retryAfterMs;

    public ArchiveException(String code, String message) {
        this(code, message, 0L);
    }

    public ArchiveException(String code, String message, long retryAfterMs) {
        super(message);
        this.code = code;
        this.retryAfterMs = Math.max(0L, retryAfterMs);
    }

    public String code() {
        return code;
    }

    /** 限流与处理中场景给出的建议等待毫秒数；0 表示不提供。 */
    public long retryAfterMs() {
        return retryAfterMs;
    }
}
