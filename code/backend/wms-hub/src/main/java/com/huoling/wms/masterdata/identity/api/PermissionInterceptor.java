package com.huoling.wms.masterdata.identity.api;

import com.huoling.wms.masterdata.identity.application.PermissionCache;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 第二道门：接口声明的权限点必须在账号权限集内。未声明注解的接口由启动校验拦住。
 *
 * <p>验签、过期、{@code jti} 黑名单与端标识已由第一道门 {@link JwtAuthenticationFilter} 完成，
 * 本类不重复查询黑名单：重查会让无权限点注解的接口（如 {@code /auth/me}）出现两套鉴权语义，
 * 也会让同一请求按 Redis 往返两次。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final JwtTokenService tokenService;
    private final PermissionCache permissionCache;

    public PermissionInterceptor(JwtTokenService tokenService, PermissionCache permissionCache) {
        this.tokenService = tokenService;
        this.permissionCache = permissionCache;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            return true;
        }
        CurrentUser user = CurrentUser.from(tokenService, request.getHeader(HttpHeaders.AUTHORIZATION));
        // 端标识来自令牌：货主端查 OWNER 角色、单仓端查 SITE 角色、运营端查 HUB 角色（05：端间不合并）。
        Set<String> permissions = permissionCache.permissionsOf(user.accountId(), user.client());
        if (!permissions.contains(required.value())) {
            throw new IdentityException(IdentityErrorCodes.AUTHZ_NO_PERMISSION,
                "缺少权限点 " + required.value());
        }
        return true;
    }
}
