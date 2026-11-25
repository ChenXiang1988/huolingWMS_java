package com.huoling.wms.site.application;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.SiteException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单仓端限流（契约「通用约定·限流与超时」：单仓端加本仓加接口，与货主流量分池）。
 *
 * <p>单仓端按仓独立部署（D08），一个仓一套实例，因此计数放进程内即可；
 * 与 Hub 的 Redis 计数分开，单仓查询通道不会因为运营端流量被打满。
 *
 * <p>超限返回 {@code RATE_LIMITED} 并带 {@code retryAfterMs}（窗口剩余时间）。
 */
@Component
public class SiteRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final long limitPerMinute;

    public SiteRateLimiter(SiteProperties properties) {
        long configured = properties.getArchive().getRateLimitPerMinute();
        this.limitPerMinute = configured <= 0 ? 120 : configured;
    }

    /** 端加本仓加接口一个池：同一仓的不同接口互不影响。 */
    public void check(Long siteId, String apiCode) {
        long now = System.currentTimeMillis();
        String key = siteId + ":" + apiCode;
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });
        if (window.count > limitPerMinute) {
            long retryAfterMs = Math.max(1000L, window.startedAt + WINDOW_MILLIS - now);
            throw new SiteException(ArchiveContractCodes.RATE_LIMITED,
                "请求过于频繁，请稍后重试", retryAfterMs);
        }
    }

    /** 一分钟固定窗口的计数。 */
    private record Window(long startedAt, int count) {
    }
}
