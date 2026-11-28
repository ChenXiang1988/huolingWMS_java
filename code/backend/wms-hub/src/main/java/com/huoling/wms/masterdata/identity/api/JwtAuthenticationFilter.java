package com.huoling.wms.masterdata.identity.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.OwnerSessionGuard;
import com.huoling.wms.masterdata.identity.domain.TokenBlacklist;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 第一道门（见 design「鉴权链路」）：验签、过期、{@code jti} 黑名单、端标识、
 * 货主端会话可用性，任一不过返回 401（前四项 {@code AUTH_1002}，会话可用性 {@code AUTH_1003}）。
 *
 * <p>落点在这里而不是权限拦截器：拦截器只对带 {@code @RequiresPermission} 的方法生效，
 * 而 {@code /auth/me}、{@code /auth/logout}、{@code /acceptances/**} 这类只需令牌的接口没有权限点注解，
 * 黑名单校验放在拦截器里会整段被跳过，登出后的令牌仍可继续使用。
 *
 * <p>免鉴权入口只有登录与续期（{@link #AUTH_FREE_PATHS}）；登出也被校验，
 * 请求到达时该 {@code jti} 尚未入黑名单，写黑名单发生在业务层，故有效令牌可正常登出。
 *
 * <p>失败响应在此直接写出统一响应包：过滤器抛出的异常不经过
 * {@code GlobalExceptionHandler}（{@code @RestControllerAdvice} 只作用于控制器派发），
 * 若改抛异常，响应体会退化成 Spring 默认错误体，与契约不符。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * 免鉴权白名单：登录与续期，以及单仓端会话入口（API-018、API-019）。
     *
     * <p>单仓端会话入口在 {@code /api/hub/auth} 下，靠站点凭证与刷新凭证自证，不依赖既有令牌：
     * 若要求先带令牌才能换令牌，单仓端永远拿不到第一个令牌。登出、当前账号与其余接口一律需要有效令牌。
     */
    public static final Set<String> AUTH_FREE_PATHS = Set.of(
        "/hub/api/auth/login", "/hub/api/auth/refresh",
        "/api/hub/auth/site-login", "/api/hub/auth/refresh");

    private final JwtTokenService tokenService;
    private final TokenBlacklist blacklist;
    private final ObjectMapper objectMapper;
    private final Set<ClientType> allowedClients;
    private final OwnerSessionGuard ownerSessions;

    /** 运营端默认形态：只接受运营端令牌。 */
    public JwtAuthenticationFilter(JwtTokenService tokenService, TokenBlacklist blacklist,
                                   ObjectMapper objectMapper, OwnerSessionGuard ownerSessions) {
        this(tokenService, blacklist, objectMapper, Set.of(ClientType.HUB), ownerSessions);
    }

    /**
     * 按端注册形态：可放行的端由注册处给出。
     *
     * <p>商品档案接口分布在 {@code /api/owner}、{@code /api/hub}、{@code /api/site} 三个前缀下，
     * 一个过滤器实例要同时服务三端；端与接口的对应关系由各控制器自己再校验一层（数据域解析），
     * 过滤器只做令牌层面的验签、过期、黑名单与端白名单。
     */
    public JwtAuthenticationFilter(JwtTokenService tokenService, TokenBlacklist blacklist,
                                   ObjectMapper objectMapper, Set<ClientType> allowedClients,
                                   OwnerSessionGuard ownerSessions) {
        this.tokenService = tokenService;
        this.blacklist = blacklist;
        this.objectMapper = objectMapper;
        this.allowedClients = allowedClients;
        this.ownerSessions = ownerSessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return AUTH_FREE_PATHS.contains(pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 一、二：验签与过期由解析完成，任一不通过即 401。
        CurrentUser user;
        try {
            user = CurrentUser.from(tokenService, request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (IdentityException e) {
            reject(response, e.getMessage());
            return;
        }
        // 三：黑名单。登出后命中即 401；Redis 不可用时按未命中处理，由令牌有效期兜底。
        if (blacklist.isBlacklisted(user.jti())) {
            reject(response, "令牌已失效，请重新登录");
            return;
        }
        // 四：端标识。注册处给定的端集合之外的令牌一律拒绝，不跨端复用。
        if (!allowedClients.contains(user.client())) {
            reject(response, "令牌端标识不匹配，请重新登录");
            return;
        }
        // 五：货主端会话可用性。货主停用后，已签发的访问令牌在下一次请求即失效（契约 1.7）。
        // 判定走带版本号的缓存，停用提交后版本号一 bump，这里的下一次请求就回源拿到停用状态。
        if (user.client() == ClientType.OWNER && !ownerSessions.usable(user.accountId())) {
            rejectAccountDisabled(response, "账号所属货主已停用，请联系运营管理员");
            return;
        }
        chain.doFilter(request, response);
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        reject(response, IdentityErrorCodes.AUTH_TOKEN_INVALID,
            message == null ? "令牌无效或已过期" : message);
    }

    /**
     * 会话可用性拒绝：401 {@code AUTH_1003}（按「账号不可用」处置，不新增错误码）。
     *
     * <p>与令牌无效区分开：调用方据此提示「联系管理员」而不是「重新登录」——
     * 重新登录也不会成功，货主停用期间该账号在任何入口都进不去。
     */
    private void rejectAccountDisabled(HttpServletResponse response, String message) throws IOException {
        reject(response, IdentityErrorCodes.AUTH_ACCOUNT_DISABLED, message);
    }

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(
            ErrorEnvelope.wireCode(code), message, ErrorEnvelope.traceId(),
            ErrorEnvelope.retryable(code)));
    }
}
