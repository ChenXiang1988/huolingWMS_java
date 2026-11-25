package com.huoling.wms.masterdata.identity.api;

import com.huoling.wms.masterdata.identity.application.AuthService;
import com.huoling.wms.masterdata.identity.application.LoginRateLimiter;
import com.huoling.wms.masterdata.identity.domain.DataScope;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 认证入口。登录与续期免鉴权，白名单见 SecurityConfig（API-001、API-002）。
 *
 * <p>身份字段里的账号标识按契约「标识、类型与枚举约定」输出十进制字符串：
 * API-001／API-002 叫 {@code accountId}，API-004 叫 {@code id}，两者同为账号主键；
 * API-004 的 {@code dataScope.ownerIds[]} 与 {@code warehouseIds[]} 元素同为十进制字符串。
 */
@RestController
@RequestMapping("/hub/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;
    private final com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService tokenService;

    public AuthController(AuthService authService,
                          LoginRateLimiter loginRateLimiter,
                          com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService tokenService) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginView>> login(@RequestBody @Valid LoginRequest request,
                                                       HttpServletRequest httpRequest) {
        // 契约 API-001：同 IP 与同账号各 10 次/分钟，超限 429 并带 Retry-After。
        loginRateLimiter.check(httpRequest.getRemoteAddr(), request.accountName());
        AuthService.LoginResult result = authService.login(request.accountName(), request.password());
        return withRefreshCookie(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginView>> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken) {
        AuthService.LoginResult result = authService.refresh(refreshToken == null ? "" : refreshToken);
        return withRefreshCookie(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CurrentUser current = CurrentUser.from(tokenService, authorization);
        authService.logout(current.accountId(), current.jti(), current.remainingSeconds());
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
            .httpOnly(true).path("/hub/api/auth").maxAge(Duration.ZERO).sameSite("Lax").build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(ApiResponse.ok(null, traceId()));
    }

    @GetMapping("/me")
    public ApiResponse<AccountProfileView> me(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CurrentUser current = CurrentUser.from(tokenService, authorization);
        AuthService.AccountProfile profile = authService.profile(current.accountId());
        return ApiResponse.ok(new AccountProfileView(Identifiers.toWire(profile.id()),
            profile.accountName(), profile.roles(), profile.permissions(),
            DataScopeView.from(profile.dataScope())), traceId());
    }

    private ResponseEntity<ApiResponse<LoginView>> withRefreshCookie(AuthService.LoginResult result) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", result.refreshToken())
            .httpOnly(true).path("/hub/api/auth").maxAge(Duration.ofDays(7)).sameSite("Lax").build();
        LoginView view = new LoginView(result.accessToken(), result.expiresIn(),
            Identifiers.toWire(result.accountId()), result.accountName(), result.roles(),
            result.permissions());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(ApiResponse.ok(view, traceId()));
    }

    private String traceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public record LoginRequest(@NotBlank String accountName, @NotBlank String password) {
    }

    /** 契约 API-001、API-002 响应：身份字段平铺，{@code accountId} 为十进制字符串。 */
    public record LoginView(String accessToken, long expiresIn,
                            @Schema(type = "string", pattern = Identifiers.PATTERN,
                                description = "账号主键，十进制字符串")
                            String accountId,
                            String accountName, List<String> roles, Set<String> permissions) {
    }

    /** 契约 API-004 响应：{@code id} 为十进制字符串，数据域五字段。 */
    public record AccountProfileView(
            @Schema(type = "string", pattern = Identifiers.PATTERN, description = "账号主键，十进制字符串")
            String id,
            String accountName,
            List<String> roles,
            Set<String> permissions,
            DataScopeView dataScope) {
    }

    /**
     * 契约 API-004 的 {@code dataScope}：两个维度各用「全部标记 + 标识列表」表达，
     * 列表元素与其余标识同为十进制字符串（契约「标识、类型与枚举约定」）。
     * 域内仍是 {@code Set<Long>}，只在这里转换；全部维度或未命中的维度输出空数组，不输出 {@code null}。
     */
    public record DataScopeView(
            @Schema(description = "货主域为全部时为 true，此时 ownerIds 为空数组")
            boolean allOwners,
            @ArraySchema(schema = @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "货主标识，十进制字符串"))
            List<String> ownerIds,
            @Schema(description = "仓域为全部时为 true，此时 warehouseIds 为空数组")
            boolean allWarehouses,
            @ArraySchema(schema = @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "仓标识，十进制字符串"))
            List<String> warehouseIds,
            @Schema(description = "派生标记：任一维度为「指定列表且为空」时为 true，调用方不得当作全部")
            boolean empty) {

        static DataScopeView from(DataScope scope) {
            return new DataScopeView(scope.allOwners(), Identifiers.toWire(scope.ownerIds()),
                scope.allWarehouses(), Identifiers.toWire(scope.warehouseIds()), scope.isEmpty());
        }
    }
}
