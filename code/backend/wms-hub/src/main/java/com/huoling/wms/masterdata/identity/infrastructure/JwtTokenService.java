package com.huoling.wms.masterdata.identity.infrastructure;

import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.infrastructure.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 访问令牌签发与解析。载荷只有账号、端、jti 与时间，不放运营端权限（19）。
 *
 * <p>单仓端令牌是唯一的例外：契约「单仓端会话与权限」要求令牌声明含唯一 {@code siteId} 与
 * 权限副本，Site 单元据此完成数据域与功能权限校验（01「认证：三套 token 策略」）。
 * 权限副本只放本端权限点集合，不放角色与数据域规则。
 */
@Component
public class JwtTokenService {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(Long accountId, ClientType client) {
        return sign(accountId, client).compact();
    }

    /**
     * 签发单仓端令牌（API-018、API-019）：声明含端标识 {@code SITE}、唯一 {@code siteId}
     * 与本端功能权限副本。
     *
     * @param siteId 本仓标识，必须唯一；多仓声明在签发入口就被拒绝，不会进到这里
     */
    public String issueSite(Long accountId, Long siteId, Collection<String> permissions) {
        if (siteId == null) {
            throw new IllegalArgumentException("单仓端令牌必须携带唯一 siteId");
        }
        List<String> copy = permissions == null ? List.of() : List.copyOf(permissions);
        return sign(accountId, ClientType.SITE)
            .claim("siteId", String.valueOf(siteId))
            .claim("permissions", copy)
            .compact();
    }

    public long accessTokenSeconds() {
        return properties.getAccessTokenMinutes() * 60L;
    }

    public ParsedToken parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
            return new ParsedToken(
                Long.valueOf(claims.getSubject()),
                ClientType.valueOf(claims.get("client", String.class)),
                claims.getId(),
                claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private io.jsonwebtoken.JwtBuilder sign(Long accountId, ClientType client) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTokenMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
            .subject(String.valueOf(accountId))
            .id(UUID.randomUUID().toString())
            .claim("client", client.name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(key);
    }

    public record ParsedToken(Long accountId, ClientType client, String jti, Instant expiresAt) {
    }
}
