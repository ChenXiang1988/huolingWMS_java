package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按端与货主分池的接口限流（本次变更 design「通用约定·限流与超时」、tasks 1.7）。
 *
 * <p>池键按端分开：货主端取归属货主、运营端取 {@code HUB}、单仓端取本仓，
 * 因此外部流量打满不会占用单仓查询通道（契约「鲁棒性约定」）。
 * 计数放 Redis，自增与过期在一个 Lua 脚本里完成，并发不丢计数。
 *
 * <p>Redis 不可用时抛连接异常，由全局异常处理器映射为 503，不放行。
 */
@Component
public class ArchiveRateLimiter {

    private static final String KEY_PREFIX = "hub:archive:rate:";
    private static final long WINDOW_MILLIS = 60_000L;

    private static final DefaultRedisScript<List> HIT_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        return {current, redis.call('PTTL', KEYS[1])}
        """, List.class);

    private final StringRedisTemplate redis;
    private final long limitPerMinute;

    public ArchiveRateLimiter(StringRedisTemplate redis,
                              @Value("${wms.archive.rate-limit-per-minute:120}") long limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute <= 0 ? 120 : limitPerMinute;
    }

    /** 货主端：端加归属货主加接口一个池。 */
    public void checkOwner(Long ownerId, String apiCode) {
        hit("owner:" + ownerId + ":" + apiCode);
    }

    /** 运营端：端加接口一个池。 */
    public void checkHub(String apiCode) {
        hit("hub:" + apiCode);
    }

    /** 单仓端：端加本仓加接口一个池，与货主池物理分开。 */
    public void checkSite(Long warehouseId, String apiCode) {
        hit("site:" + warehouseId + ":" + apiCode);
    }

    @SuppressWarnings("unchecked")
    private void hit(String poolKey) {
        List<Long> result = redis.execute(HIT_SCRIPT, List.of(KEY_PREFIX + poolKey),
            String.valueOf(WINDOW_MILLIS));
        if (result == null || result.size() < 2) {
            throw new ArchiveException(ArchiveErrorCodes.DEPENDENCY_UNAVAILABLE, "限流计数不可用，请稍后重试");
        }
        long count = result.get(0);
        long ttlMillis = result.get(1);
        if (count > limitPerMinute) {
            long retryAfterMs = Math.max(1000L, ttlMillis);
            throw new ArchiveException(ArchiveErrorCodes.RATE_LIMITED,
                "请求过于频繁，请稍后重试", retryAfterMs);
        }
    }
}
