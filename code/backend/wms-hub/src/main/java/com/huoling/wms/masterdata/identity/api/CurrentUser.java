package com.huoling.wms.masterdata.identity.api;

import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;

import java.time.Duration;
import java.time.Instant;

/**
 * 从 Authorization 头解析出的当前会话。只读令牌载荷，权限另行解析。
 * 端标识来自令牌载荷，由 {@link JwtAuthenticationFilter} 校验，权限拦截器与控制器只消费结果。
 */
public record CurrentUser(Long accountId, ClientType client, String jti, long remainingSeconds) {

    public static CurrentUser from(JwtTokenService tokens, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IdentityException(IdentityErrorCodes.AUTH_TOKEN_INVALID, "缺少访问令牌");
        }
        JwtTokenService.ParsedToken parsed = tokens.parse(authorization.substring(7));
        if (parsed == null) {
            throw new IdentityException(IdentityErrorCodes.AUTH_TOKEN_INVALID, "令牌无效或已过期");
        }
        long remaining = Math.max(0,
            Duration.between(Instant.now(), parsed.expiresAt()).getSeconds());
        return new CurrentUser(parsed.accountId(), parsed.client(), parsed.jti(), remaining);
    }
}
