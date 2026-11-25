package com.huoling.wms.masterdata.archive.infrastructure;

import com.huoling.wms.masterdata.identity.infrastructure.config.JwtProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 单仓端刷新凭证（API-019）。
 *
 * <p>与运营端刷新令牌分开一个前缀，因为单仓端刷新凭证必须同时记住本仓标识：
 * 续期只换令牌不换站，续期时不允许把 {@code siteId} 换成别的仓（契约「并发冲突：同一刷新凭证并发只产生一个新令牌」）。
 * 一次性消费用 Redis GETDEL 完成：并发下同一个刷新凭证只有一个请求取到站点，其余按凭证无效拒绝。
 */
@Component
public class SiteRefreshTokenStore {

    private static final String PREFIX = "hub:site-refresh:";
    private static final String SEPARATOR = "|";

    private final StringRedisTemplate redis;
    private final JwtProperties properties;

    public SiteRefreshTokenStore(StringRedisTemplate redis, JwtProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 签发刷新凭证，值里带账号与本仓标识。 */
    public String issue(Long accountId, Long siteId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Duration ttl = Duration.ofDays(properties.getRefreshTokenDays());
        redis.opsForValue().set(PREFIX + token,
            (accountId == null ? 0L : accountId) + SEPARATOR + siteId, ttl);
        return token;
    }

    /** 一次性消费：取不到或形态不对都按凭证无效处理。 */
    public Optional<RefreshSubject> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = redis.opsForValue().getAndDelete(PREFIX + token);
        if (value == null) {
            return Optional.empty();
        }
        int index = value.indexOf(SEPARATOR);
        if (index <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RefreshSubject(Long.valueOf(value.substring(0, index)),
                Long.valueOf(value.substring(index + 1))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 刷新凭证里的主体：账号与本仓标识。 */
    public record RefreshSubject(Long accountId, Long siteId) {
    }
}
