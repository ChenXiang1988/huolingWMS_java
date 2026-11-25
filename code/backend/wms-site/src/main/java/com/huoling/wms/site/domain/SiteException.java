package com.huoling.wms.site.domain;

/**
 * 单仓端业务异常：带契约错误码与可选的重试间隔。
 *
 * <p>错误码取值只来自 {@code ArchiveContractCodes}（common），不新增契约外错误码。
 * 与 Hub 侧的 {@code ArchiveException} 同形不同类：两个单元不共享应用层，只共享 kernel 与 common。
 */
public class SiteException extends RuntimeException {

    private final String code;
    private final long retryAfterMs;

    public SiteException(String code, String message) {
        this(code, message, 0L);
    }

    public SiteException(String code, String message, long retryAfterMs) {
        super(message);
        this.code = code;
        this.retryAfterMs = retryAfterMs;
    }

    public String code() {
        return code;
    }

    /** 可重试错误的重试间隔；零表示不适用（契约「错误响应结构」）。 */
    public long retryAfterMs() {
        return retryAfterMs;
    }
}
