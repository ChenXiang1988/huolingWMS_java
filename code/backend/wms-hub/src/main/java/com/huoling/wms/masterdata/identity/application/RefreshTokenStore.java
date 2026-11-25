package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.TokenBlacklist;
import com.huoling.wms.masterdata.identity.infrastructure.config.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 刷新令牌与登出黑名单。刷新令牌一次性使用，登出后按 jti 拉黑到原到期时间。
 */
@Component
public class RefreshTokenStore implements TokenBlacklist {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String REFRESH_PREFIX = "hub:refresh:";
    private static final String ACCOUNT_PREFIX = "hub:refresh:account:";
    private static final String BLACKLIST_PREFIX = "hub:jti:blacklist:";

    private final StringRedisTemplate redis;
    private final JwtProperties properties;

    public RefreshTokenStore(StringRedisTemplate redis, JwtProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String issue(Long accountId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Duration ttl = Duration.ofDays(properties.getRefreshTokenDays());
        redis.opsForValue().set(REFRESH_PREFIX + token, String.valueOf(accountId), ttl);
        redis.opsForSet().add(ACCOUNT_PREFIX + accountId, token);
        redis.expire(ACCOUNT_PREFIX + accountId, ttl);
        return token;
    }

    /**
     * 一次性消费刷新令牌（契约 API-002）。用 Redis GETDEL 原子取出并删除：
     * 并发下同一刷新令牌只有一个请求取到账号，其余的取到空值返回 401 {@code AUTH_1004}。
     */
    public Optional<Long> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = REFRESH_PREFIX + token;
        String accountId = redis.opsForValue().getAndDelete(key);
        if (accountId == null) {
            return Optional.empty();
        }
        redis.opsForSet().remove(ACCOUNT_PREFIX + accountId, token);
        return Optional.of(Long.valueOf(accountId));
    }

    public void revokeAll(Long accountId) {
        Set<String> tokens = redis.opsForSet().members(ACCOUNT_PREFIX + accountId);
        if (tokens != null) {
            tokens.forEach(token -> redis.delete(REFRESH_PREFIX + token));
        }
        redis.delete(ACCOUNT_PREFIX + accountId);
    }

    public void blacklist(String jti, long remainingSeconds) {
        if (jti == null || remainingSeconds <= 0) {
            return;
        }
        redis.opsForValue().set(BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(remainingSeconds));
    }

    /**
     * 登出黑名单查询（第一道门的第三项校验）。
     *
     * <p>按契约 API-003「降级时黑名单写失败以令牌有效期兜底」：Redis 读失败时按未命中处理并记告警，
     * 不把存储抖动放大成全站 401。已登出的令牌最迟在原到期时间失效，窗口上限为访问令牌有效期（30 分钟）。
     * 写黑名单（{@link #blacklist}）与刷新令牌消费仍照常抛出，由统一出口映射为 503 {@code COMMON_9003}。
     */
    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti));
        } catch (DataAccessException e) {
            log.warn("登出黑名单查询失败，按未命中处理，令牌到期后自然失效：{}", e.getMessage());
            return false;
        }
    }
}
