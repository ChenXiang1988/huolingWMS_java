package com.huoling.wms.site.api;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.SiteException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单仓端令牌验签（契约「单仓端会话与权限」）。
 *
 * <p>三件事必须同时成立才放行：令牌由 Hub 签发（同一密钥验签、未过期）、端标识为 {@code SITE}、
 * 声明里恰好一个 {@code siteId} 且与本仓配置一致。缺 {@code siteId} 或声明了多个，
 * 一律 {@code FORBIDDEN_SITE}（specs product-archive-access「多仓声明被拒绝」）。
 *
 * <p>令牌无效与过期用平台既有的 401 口径（身份模块数字码 1002），
 * 不在商品档案错误码表里新增码（契约「不新增契约外错误码」）。
 */
@Component
public class SiteTokenVerifier {

    /** 身份模块的 AUTH_1002 数字段：令牌无效或已过期，四端同一口径。 */
    public static final String TOKEN_INVALID_CODE = "1002";

    private final SecretKey key;
    private final SiteProperties properties;

    public SiteTokenVerifier(SiteProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(
            properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验签并解析会话。
     *
     * @throws SiteException 令牌缺失、无效或过期（401）；端标识不符、多仓声明或非本仓（403）
     */
    public SiteSession verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SiteException(TOKEN_INVALID_CODE, "缺少单仓端访问令牌");
        }
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(authorization.substring(7)).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new SiteException(TOKEN_INVALID_CODE, "令牌无效或已过期");
        }
        if (!"SITE".equals(claims.get("client", String.class))) {
            throw new SiteException(ArchiveContractCodes.FORBIDDEN_SITE, "该接口只接受单仓端令牌");
        }
        Long siteId = singleSiteId(claims.get("siteId"));
        if (siteId == null) {
            throw new SiteException(ArchiveContractCodes.FORBIDDEN_SITE,
                "令牌缺少唯一 siteId，拒绝访问");
        }
        Long localSite = properties.getSite().getId();
        if (localSite != null && localSite > 0 && !localSite.equals(siteId)) {
            throw new SiteException(ArchiveContractCodes.FORBIDDEN_SITE,
                "令牌站点与本仓不一致，拒绝访问");
        }
        Long accountId;
        try {
            accountId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new SiteException(TOKEN_INVALID_CODE, "令牌主体不是合法账号标识");
        }
        return new SiteSession(siteId, accountId, permissions(claims.get("permissions")));
    }

    /**
     * 唯一仓标识：字符串形态必须是单个十进制数，数组形态必须恰好一个元素。
     * 其余形态（缺声明、多元素、逗号串）按多仓声明拒绝。
     */
    private Long singleSiteId(Object claim) {
        String raw = null;
        if (claim instanceof Collection<?> collection) {
            if (collection.size() != 1) {
                return null;
            }
            Object first = collection.iterator().next();
            raw = first == null ? null : String.valueOf(first);
        } else if (claim instanceof String text) {
            raw = text;
        }
        if (raw == null || raw.isBlank() || raw.contains(",")) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Set<String> permissions(Object claim) {
        Set<String> codes = new LinkedHashSet<>();
        if (claim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    codes.add(String.valueOf(item));
                }
            }
        } else if (claim instanceof String text && !text.isBlank()) {
            codes.addAll(List.of(text.split(",")));
        }
        return Set.copyOf(codes);
    }
}
