package com.huoling.wms.masterdata.identity.domain;

/**
 * 已撤销会话的读取端口：登出把 {@code jti} 记入黑名单，直到令牌原到期时间。
 *
 * <p>读取方是第一道门的 JWT 过滤器（api 层），写入方是登出用例（application 层）。
 * 按 03「infrastructure 只依赖本模块 domain 的接口」，过滤器的装配走这个接口，
 * 不让 infrastructure 的配置类反向依赖 application 的具体实现。
 */
public interface TokenBlacklist {

    /** 该 {@code jti} 是否已撤销。存储不可用时按未命中处理，由令牌有效期兜底（契约 API-003 降级）。 */
    boolean isBlacklisted(String jti);
}
