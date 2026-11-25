package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.application.SiteAuthService;
import com.huoling.wms.masterdata.archive.infrastructure.config.SiteAuthCookieProperties;
import com.huoling.wms.masterdata.identity.infrastructure.config.JwtProperties;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 单仓端会话入口（API-018 换取、API-019 续期），路径前缀 {@code /api/hub/auth}。
 *
 * <p>这两个接口不依赖既有令牌：换取用站点凭证，续期用刷新凭证；端标识固定 {@code SITE}。
 * 响应体只含令牌、{@code siteId}、功能权限列表与有效期（契约 API-018/019 响应），
 * 刷新凭证走 httpOnly Cookie 下发，不占响应字段。
 *
 * <p><b>续期凭证的两条承载通道（tasks 1.18）</b>：契约 API-019 的请求体含
 * {@code refreshCredential}，并注明「刷新凭证可由 httpOnly cookie 带入」。单仓端与 Hub 不同源时，
 * 浏览器可能不再把 Cookie 带过来（第三方 Cookie 被拦），因此这里两条通道都收：
 * 请求体显式回传优先，Cookie 兜底。请求体与 Cookie 都没有才按 {@code PARAM_INVALID} 拒绝。
 *
 * <p>Cookie 属性由 {@link SiteAuthCookieProperties} 配置：同站部署用 {@code SameSite=Lax}，
 * 跨源部署改 {@code SameSite=None} 并强制 {@code Secure}，不改代码。
 *
 * <p>多仓声明、凭证错误与站点未登记一律 {@code FORBIDDEN_SITE}（403），不区分原因以免探测站点。
 */
@RestController
@RequestMapping("/api/hub/auth")
public class SiteAuthController {

    /** 单仓端刷新凭证的 Cookie 名，作用域限定在认证入口。 */
    private static final String REFRESH_COOKIE = "site_refresh_token";
    private static final String REFRESH_PATH = "/api/hub/auth";
    private static final String SAME_SITE_NONE = "None";

    private final SiteAuthService siteAuthService;
    private final JwtProperties jwtProperties;
    private final SiteAuthCookieProperties cookieProperties;

    public SiteAuthController(SiteAuthService siteAuthService, JwtProperties jwtProperties,
                              SiteAuthCookieProperties cookieProperties) {
        this.siteAuthService = siteAuthService;
        this.jwtProperties = jwtProperties;
        this.cookieProperties = cookieProperties;
    }

    /** API-018：站点凭证换令牌。 */
    @PostMapping("/site-login")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "签发成功：返回 accessToken、siteId、permissions 与 expiresIn；"
            + "刷新凭证以 httpOnly cookie 下发，不进响应体",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_BAD_REQUEST,
            description = ArchiveApiDocs.PARAM_INVALID,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_FORBIDDEN,
            description = ArchiveApiDocs.FORBIDDEN_SITE + "；含凭证错误、站点未登记与多仓声明",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_RATE_LIMITED,
            description = ArchiveApiDocs.RATE_LIMITED,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_UNAVAILABLE,
            description = ArchiveApiDocs.DEPENDENCY_UNAVAILABLE,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_INTERNAL,
            description = ArchiveApiDocs.INTERNAL_ERROR,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class)))
    })
    public ResponseEntity<ArchiveResponse<SiteTokenView>> login(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody LoginRequest request) {
        SiteAuthService.Issued issued = siteAuthService.login(request.siteCode(), request.credential(),
            keyOf(idempotencyKey, request.clientRequestId()));
        return withRefreshCookie(issued);
    }

    /** API-019：刷新凭证换新令牌；请求体显式回传或 Cookie 带入，二者其一即可。 */
    @PostMapping("/refresh")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "续期成功：返回新 accessToken、siteId、permissions 与 expiresIn；"
            + "新刷新凭证仍以 httpOnly cookie 下发",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_BAD_REQUEST,
            description = ArchiveApiDocs.PARAM_INVALID + "；请求体与 cookie 都未带刷新凭证时也按此拒绝",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_FORBIDDEN,
            description = ArchiveApiDocs.FORBIDDEN_SITE + "；含刷新凭证失效、已使用与端标识不是 SITE",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_RATE_LIMITED,
            description = ArchiveApiDocs.RATE_LIMITED,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_UNAVAILABLE,
            description = ArchiveApiDocs.DEPENDENCY_UNAVAILABLE,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_INTERNAL,
            description = ArchiveApiDocs.INTERNAL_ERROR,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class)))
    })
    public ResponseEntity<ArchiveResponse<SiteTokenView>> refresh(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieCredential,
            @RequestBody(required = false) RefreshRequest request) {
        RefreshRequest body = request == null ? new RefreshRequest(null, null) : request;
        // 请求体显式回传优先：跨源部署拿不到 Cookie 时靠它续期；同站部署仍可只靠 Cookie（契约 API-019 请求）。
        String refreshCredential = body.refreshCredential() == null || body.refreshCredential().isBlank()
            ? cookieCredential : body.refreshCredential();
        SiteAuthService.Issued issued = siteAuthService.refresh(refreshCredential,
            keyOf(idempotencyKey, body.clientRequestId()));
        return withRefreshCookie(issued);
    }

    /** 幂等键取 {@code Idempotency-Key} 头，缺省回落到请求体的 {@code clientRequestId}（契约 API-018 幂等键）。 */
    private String keyOf(String headerKey, String clientRequestId) {
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey;
        }
        return clientRequestId;
    }

    private ResponseEntity<ArchiveResponse<SiteTokenView>> withRefreshCookie(
            SiteAuthService.Issued issued) {
        SiteTokenView view = new SiteTokenView(issued.accessToken(), issued.siteId(),
            issued.permissions(), issued.expiresIn());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie(issued.refreshToken()).toString())
            .body(ArchiveResponse.ok(view));
    }

    /**
     * 刷新凭证 Cookie：跨源部署由配置决定 {@code SameSite} 与 {@code Secure}。
     *
     * <p>{@code SameSite=None} 的 Cookie 不带 {@code Secure} 会被浏览器直接丢弃，因此这一档强制安全传输。
     */
    private ResponseCookie refreshCookie(String refreshCredential) {
        String sameSite = cookieProperties.getRefreshCookieSameSite();
        boolean crossSite = SAME_SITE_NONE.equalsIgnoreCase(sameSite);
        return ResponseCookie.from(REFRESH_COOKIE, refreshCredential)
            .httpOnly(true)
            .path(REFRESH_PATH)
            .maxAge(Duration.ofDays(jwtProperties.getRefreshTokenDays()))
            .sameSite(sameSite)
            .secure(cookieProperties.isRefreshCookieSecure() || crossSite)
            .build();
    }

    /** API-018 请求体：站点编码、站点凭证与客户端请求号。 */
    public record LoginRequest(String siteCode, String credential, String clientRequestId) {
    }

    /**
     * API-019 请求体：刷新凭证与客户端请求号。
     *
     * <p>字段名取契约字段表的 {@code refreshCredential}（不是内部命名的 {@code refreshToken}）：
     * 跨源部署由调用方显式回传，同站部署留空、由 httpOnly Cookie 带入。
     */
    public record RefreshRequest(String refreshCredential, String clientRequestId) {
    }

    /** API-018、API-019 响应体：令牌、唯一 siteId、权限副本与有效期（十进制字符串标识）。 */
    public record SiteTokenView(
            String accessToken,
            @Schema(type = "string", pattern = "^[1-9][0-9]*$", description = "本仓标识，十进制字符串")
            String siteId,
            @ArraySchema(schema = @Schema(description = "功能权限副本"))
            List<String> permissions,
            long expiresIn) {
    }
}
