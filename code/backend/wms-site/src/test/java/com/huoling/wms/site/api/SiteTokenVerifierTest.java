package com.huoling.wms.site.api;

import com.huoling.wms.common.archive.ArchivePermissionCodes;
import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.SiteException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单仓会话（tasks 1.10、契约「单仓端会话与权限」）：令牌必须带端标识 SITE、唯一 siteId 与权限副本；
 * 多仓声明、非本仓与端标识不符一律 {@code FORBIDDEN_SITE}。
 */
class SiteTokenVerifierTest {

    private static final String SECRET = "test-only-secret-key-for-site-token-verification";
    private static final long SITE_ID = 1001L;

    private SecretKey key;
    private SiteTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        SiteProperties properties = new SiteProperties();
        properties.getJwt().setSecret(SECRET);
        properties.getSite().setId(SITE_ID);
        verifier = new SiteTokenVerifier(properties);
    }

    @Test
    @DisplayName("合法令牌解析出唯一本仓与权限副本")
    void 合法令牌() {
        SiteSession session = verifier.verify("Bearer " + token("1001",
            List.of(ArchivePermissionCodes.SITE_READ, ArchivePermissionCodes.SITE_CHANGE), "SITE"));

        assertThat(session.siteId()).isEqualTo(SITE_ID);
        assertThat(session.hasPermission(ArchivePermissionCodes.SITE_READ)).isTrue();
        assertThat(session.hasPermission(ArchivePermissionCodes.HUB_REVIEW)).isFalse();
    }

    @Test
    @DisplayName("多仓声明被拒，不返回任何档案数据")
    void 多仓声明被拒() {
        String multiSite = token(List.of("1001", "1002"),
            List.of(ArchivePermissionCodes.SITE_READ), "SITE");

        assertThatThrownBy(() -> verifier.verify("Bearer " + multiSite))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("缺少 siteId 声明被拒")
    void 缺少仓标识被拒() {
        String noSite = Jwts.builder().subject("7").id("jti-1").claim("client", "SITE")
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)))
            .signWith(key).compact();

        assertThatThrownBy(() -> verifier.verify("Bearer " + noSite))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("非本仓令牌被拒")
    void 非本仓被拒() {
        String otherSite = token("2002", List.of(ArchivePermissionCodes.SITE_READ), "SITE");

        assertThatThrownBy(() -> verifier.verify("Bearer " + otherSite))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("端标识不是 SITE 的令牌被拒")
    void 端标识不符被拒() {
        String hubToken = token("1001", List.of(ArchivePermissionCodes.SITE_READ), "HUB");

        assertThatThrownBy(() -> verifier.verify("Bearer " + hubToken))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("缺少令牌按令牌无效处理")
    void 缺少令牌() {
        assertThatThrownBy(() -> verifier.verify(null))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(SiteTokenVerifier.TOKEN_INVALID_CODE);
    }

    private String token(String siteId, List<String> permissions, String client) {
        return token((Object) siteId, permissions, client);
    }

    private String token(List<String> siteIds, List<String> permissions, String client) {
        return token((Object) siteIds, permissions, client);
    }

    private String token(Object siteId, List<String> permissions, String client) {
        return Jwts.builder()
            .subject("7")
            .id("jti-" + siteId)
            .claim("client", client)
            .claim("siteId", siteId)
            .claim("permissions", permissions)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)))
            .signWith(key)
            .compact();
    }
}
