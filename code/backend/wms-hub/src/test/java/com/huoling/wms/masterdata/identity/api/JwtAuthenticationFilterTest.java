package com.huoling.wms.masterdata.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.TokenBlacklist;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import com.huoling.wms.masterdata.identity.infrastructure.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第一道门的行为断言：验签 → 过期 → {@code jti} 黑名单 → 端标识，
 * 覆盖无权限点注解的接口（{@code /auth/me}、{@code /auth/logout}、{@code /acceptances/**}）。
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "local-dev-only-secret-key-please-change-32bytes";
    private static final String ACCOUNT_ID = "1001";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> blacklisted = new HashSet<>();
    private final TokenBlacklist blacklist = jti -> jti != null && blacklisted.contains(jti);

    /** 货主端会话可用性：默认全部可用，用例按需把账号放进不可用集合。 */
    private final Set<Long> unusableAccounts = new HashSet<>();

    private JwtTokenService tokens;
    private JwtAuthenticationFilter filter;
    private JwtAuthenticationFilter ownerCapableFilter;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        tokens = new JwtTokenService(properties);
        filter = new JwtAuthenticationFilter(tokens, blacklist, mapper, accountId -> true);
        // 商品档案三端接口那一道门的形态：可放行货主端令牌，用于验第五步「会话可用性」。
        ownerCapableFilter = new JwtAuthenticationFilter(tokens, blacklist, mapper,
            Set.of(ClientType.HUB, ClientType.OWNER, ClientType.SITE),
            accountId -> !unusableAccounts.contains(accountId));
    }

    @Test
    @DisplayName("免鉴权白名单：登录与续期不带令牌也能通过")
    void 免鉴权入口放行() throws Exception {
        for (String path : JwtAuthenticationFilter.AUTH_FREE_PATHS) {
            Attempt attempt = run(path, null);
            assertThat(attempt.passedThrough()).as(path).isTrue();
            assertThat(attempt.status()).as(path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("有效令牌：需鉴权接口全部放行，登出自身不被拦")
    void 有效令牌放行() throws Exception {
        String token = tokens.issue(Long.valueOf(ACCOUNT_ID), ClientType.HUB);
        for (String path : List.of("/hub/api/auth/logout", "/hub/api/auth/me", "/hub/api/accounts",
            "/hub/api/roles", "/hub/api/audit-logs", "/hub/api/acceptances/acc-1")) {
            Attempt attempt = run(path, "Bearer " + token);
            assertThat(attempt.passedThrough()).as(path).isTrue();
            assertThat(attempt.status()).as(path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("黑名单命中：无权限点注解的接口同样 401 AUTH_1002")
    void 登出后旧令牌全部拒绝() throws Exception {
        String token = tokens.issue(Long.valueOf(ACCOUNT_ID), ClientType.HUB);
        blacklisted.add(jtiOf(token));
        for (String path : List.of("/hub/api/auth/me", "/hub/api/accounts", "/hub/api/roles",
            "/hub/api/acceptances/acc-1")) {
            Attempt attempt = run(path, "Bearer " + token);
            assertThat(attempt.passedThrough()).as(path).isFalse();
            assertThat(attempt.status()).as(path).isEqualTo(401);
            assertThat(attempt.body().get("code").asInt()).as(path).isEqualTo(1002);
            assertThat(attempt.body().get("message").asText()).isEqualTo("令牌已失效，请重新登录");
            assertThat(attempt.body().get("retryable").asBoolean()).isTrue();
            assertThat(attempt.body().get("data").isNull()).isTrue();
            assertThat(attempt.body().get("traceId").asText()).startsWith("tr-");
        }
    }

    @Test
    @DisplayName("缺令牌与非 JWT 串：401 AUTH_1002，响应体走统一响应包")
    void 缺失或非法令牌拒绝() throws Exception {
        for (String header : new String[]{null, "Bearer not-a-jwt", "Basic abc"}) {
            Attempt attempt = run("/hub/api/auth/me", header);
            assertThat(attempt.status()).as(String.valueOf(header)).isEqualTo(401);
            assertThat(attempt.body().get("code").asInt()).isEqualTo(1002);
        }
    }

    @Test
    @DisplayName("过期令牌：401 AUTH_1002")
    void 过期令牌拒绝() throws Exception {
        Instant past = Instant.now().minus(10, ChronoUnit.MINUTES);
        String expired = Jwts.builder()
            .subject(ACCOUNT_ID)
            .id("jti-expired")
            .claim("client", ClientType.HUB.name())
            .issuedAt(Date.from(past.minus(30, ChronoUnit.MINUTES)))
            .expiration(Date.from(past))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();
        Attempt attempt = run("/hub/api/auth/me", "Bearer " + expired);
        assertThat(attempt.status()).isEqualTo(401);
        assertThat(attempt.body().get("code").asInt()).isEqualTo(1002);
    }

    @Test
    @DisplayName("端标识不匹配：运营端接口只接受 HUB 令牌")
    void 跨端令牌拒绝() throws Exception {
        String siteToken = tokens.issue(Long.valueOf(ACCOUNT_ID), ClientType.SITE);
        Attempt attempt = run("/hub/api/auth/me", "Bearer " + siteToken);
        assertThat(attempt.passedThrough()).isFalse();
        assertThat(attempt.status()).isEqualTo(401);
        assertThat(attempt.body().get("code").asInt()).isEqualTo(1002);
    }

    @Test
    @DisplayName("货主停用：已签发的货主端令牌下一次请求即 401 AUTH_1003，不再是 403")
    void 货主停用后令牌失效() throws Exception {
        Long ownerAccountId = 2002L;
        String token = tokens.issue(ownerAccountId, ClientType.OWNER);

        Attempt usable = run(ownerCapableFilter, "/api/hub/owners", "Bearer " + token);
        assertThat(usable.passedThrough()).as("货主可用时令牌放行，业务鉴权由控制器负责").isTrue();

        unusableAccounts.add(ownerAccountId);
        Attempt rejected = run(ownerCapableFilter, "/api/hub/owners", "Bearer " + token);
        assertThat(rejected.passedThrough()).isFalse();
        assertThat(rejected.status()).isEqualTo(401);
        assertThat(rejected.body().get("code").asInt()).as("按账号不可用处置，不新增错误码").isEqualTo(1003);
        assertThat(rejected.body().get("retryable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("存储降级：黑名单读取端口返回未命中时不拦请求（降级实现见 RefreshTokenStore）")
    void 黑名单降级不拒绝() throws Exception {
        JwtAuthenticationFilter degraded = new JwtAuthenticationFilter(tokens, jti -> false, mapper,
            accountId -> true);
        String token = tokens.issue(Long.valueOf(ACCOUNT_ID), ClientType.HUB);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        degraded.doFilter(request("/hub/api/auth/me", "Bearer " + token), response,
            (req, res) -> called.set(true));
        assertThat(called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private Attempt run(String path, String authorization) throws Exception {
        return run(filter, path, authorization);
    }

    private Attempt run(JwtAuthenticationFilter subject, String path, String authorization)
            throws Exception {
        MockHttpServletRequest request = request(path, authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        FilterChain chain = (req, res) -> called.set(true);
        subject.doFilter(request, response, chain);
        JsonNode body = response.getContentAsString().isEmpty()
            ? mapper.createObjectNode()
            : mapper.readTree(response.getContentAsString());
        return new Attempt(called.get(), response.getStatus(), body);
    }

    private MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.setContextPath("");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private String jtiOf(String token) {
        return tokens.parse(token).jti();
    }

    private record Attempt(boolean passedThrough, int status, JsonNode body) {
    }
}
