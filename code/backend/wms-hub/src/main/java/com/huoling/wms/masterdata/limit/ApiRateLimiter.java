package com.huoling.wms.masterdata.limit;

import com.huoling.wms.masterdata.acceptance.domain.AcceptanceErrorCodes;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按端与接口分池的接口限流（契约 1.1「限流与熔断」、design「可观测与兼容回滚」）。
 *
 * <p>池键按端分开：货主端取归属货主、运营端取 {@code HUB}、单仓端取本仓，
 * 因此外部流量打满不会占用单仓查询通道（D18）。计数放 Redis，
 * 自增与过期在一个 Lua 脚本里完成，并发不丢计数。
 *
 * <p>为什么放在中立包 {@code masterdata.limit} 而不是某个业务包：商品档案与货主档案
 * 都要用它，而架构断言要求 {@code masterdata.owner} 与 {@code masterdata.archive}
 * 双向不得依赖（design AR-11）。限流是端与接口维度的手段，不属于任何一个业务，
 * 下沉到中立包后两侧引用同一份脚本与同一个阈值，不会出现两套计数口径。
 *
 * <p>Redis 不可用时抛连接异常，由接口层的异常处理器映射为 503，不放行。
 */
@Component
public class ApiRateLimiter {

    /** Redis 键前缀：沿用商品档案上线时的取值，改前缀会让全端计数在一分钟内重新开始。 */
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

    public ApiRateLimiter(StringRedisTemplate redis,
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
            throw new AcceptanceException(AcceptanceErrorCodes.DEPENDENCY_UNAVAILABLE,
                "限流计数不可用，请稍后重试");
        }
        long count = result.get(0);
        long ttlMillis = result.get(1);
        if (count > limitPerMinute) {
            long retryAfterMs = Math.max(1000L, ttlMillis);
            throw new AcceptanceException(AcceptanceErrorCodes.RATE_LIMITED,
                "请求过于频繁，请稍后重试", retryAfterMs);
        }
    }
}
