package com.huoling.wms.masterdata.owner.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.identity.api.CurrentUser;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
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

/**
 * 货主档案接口的端白名单（契约 1.1「端与令牌的强校验」）。
 *
 * <p>为什么需要单独一层：{@code /api/*} 前缀上注册的第一道门同时放行三端令牌（商品档案的
 * {@code /api/owner}、{@code /api/site} 也用同一个前缀），而货主档案的管理接口只接受运营端令牌。
 * 若不在这里拦，货主端令牌会先撞上权限拦截器拿到 {@code AUTHZ_2001}——同样 403，但错误码
 * 与契约写的 {@code FORBIDDEN_OWNER} 不一致，调用方按码分派就会分错。
 *
 * <p>顺序上排在第一道门之后：验签、过期与 {@code jti} 黑名单仍由那道门负责（401 语义不变），
 * 这里只判端标识，不做第二次验签判定。令牌缺失或非法时不在这里报错，
 * 交给后面的链路给出统一的 401。
 */
public class OwnerClientFilter extends OncePerRequestFilter {

    private final JwtTokenService tokenService;
    private final ObjectMapper objectMapper;

    public OwnerClientFilter(JwtTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CurrentUser user;
        try {
            user = CurrentUser.from(tokenService, request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (IdentityException e) {
            // 令牌本身有问题：交给后面的链路按 401 处理，不在这里换成 403。
            chain.doFilter(request, response);
            return;
        }
        if (user.client() != ClientType.HUB) {
            reject(response, user.client());
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, ClientType client) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), OwnerResponse.failure(
            OwnerErrorCodes.FORBIDDEN_OWNER,
            "该接口只接受运营端令牌，当前令牌端标识为 " + client, false));
    }
}
