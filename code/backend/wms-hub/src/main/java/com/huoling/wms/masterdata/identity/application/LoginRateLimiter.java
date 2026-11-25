package com.huoling.wms.masterdata.identity.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 登录限流（契约 API-001）：同 IP 与同账号各 10 次/分钟，取 {@code wms.auth.login-limit-per-minute}。
 *
 * <p>计数放 Redis，键为 {@code hub:login:rate:ip:<ip>} 与 {@code hub:login:rate:acct:<账号>}。
 * 自增与设置过期在一个 Lua 脚本里完成，因此并发下每个请求拿到互不相同的计数、不会丢计数，
 * 只有计数超过上限的请求被拒。窗口是「以窗口内第一个请求为起点」的固定窗口，
 * 跨窗口边界的最坏情况是相邻两个窗口各放行 limit 次；要更严格需换滑动窗口或漏桶。
 *
 * <p>Redis 不可用时抛连接异常，由异常处理器统一映射为 503 {@code COMMON_9003}，不放行。
 */
@Component
public class LoginRateLimiter {

    private static final String IP_PREFIX = "hub:login:rate:ip:";
    private static final String ACCOUNT_PREFIX = "hub:login:rate:acct:";
    private static final long WINDOW_MILLIS = 60_000L;

    /** 自增并在首次自增时设置窗口过期，返回 {当前计数, 剩余毫秒}。 */
    private static final DefaultRedisScript<List> HIT_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        return {current, redis.call('PTTL', KEYS[1])}
        """, List.class);

    private final StringRedisTemplate redis;
    private final long limitPerMinute;

    public LoginRateLimiter(StringRedisTemplate redis,
                            @Value("${wms.auth.login-limit-per-minute:10}") long limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute <= 0 ? 10 : limitPerMinute;
    }

    /**
     * 按 IP 与账号各记一次，任一维度超限即拒绝。
     *
     * @param clientIp    调用方 IP（取 {@code request.getRemoteAddr()}）
     * @param accountName 登录账号名
     */
    public void check(String clientIp, String accountName) {
        long ipRetryAfter = hit(IP_PREFIX + clientIp, "IP " + clientIp);
        long accountRetryAfter = hit(ACCOUNT_PREFIX + accountName, "账号 " + accountName);
        long retryAfter = Math.max(ipRetryAfter, accountRetryAfter);
        if (retryAfter > 0) {
            throw new RateLimitExceededException(retryAfter, "登录请求过于频繁，请稍后重试");
        }
    }

    /** 返回 0 表示未超限；大于 0 表示超限且建议等待的秒数。 */
    @SuppressWarnings("unchecked")
    private long hit(String key, String dimension) {
        List<Long> result = redis.execute(HIT_SCRIPT, List.of(key), String.valueOf(WINDOW_MILLIS));
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("登录限流计数失败：" + dimension);
        }
        long count = result.get(0);
        long ttlMillis = result.get(1);
        if (count <= limitPerMinute) {
            return 0;
        }
        return Math.max(1, (ttlMillis + 999) / 1000);
    }
}
