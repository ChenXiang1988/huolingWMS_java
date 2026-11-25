package com.huoling.wms.site.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.site.domain.SiteException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 单仓端第一道门：验签、过期、端标识与唯一 {@code siteId}（契约「单仓端会话与权限」）。
 *
 * <p>会话放进请求属性，控制器只消费结果，不各自解析令牌；失败响应在这里直接写出统一响应包——
 * 过滤器抛出的异常不经过 {@code @RestControllerAdvice}，改抛会把响应体退化成容器默认错误体。
 */
public class SiteTokenFilter extends OncePerRequestFilter {

    /** 会话属性名，控制器用它取本仓域与权限副本。 */
    public static final String SESSION_ATTRIBUTE = "wms.site.session";

    private final SiteTokenVerifier verifier;
    private final ObjectMapper objectMapper;

    public SiteTokenFilter(SiteTokenVerifier verifier, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/error")
            || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            SiteSession session = verifier.verify(request.getHeader("Authorization"));
            request.setAttribute(SESSION_ATTRIBUTE, session);
        } catch (SiteException e) {
            reject(response, e);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, SiteException e) throws IOException {
        HttpStatus status = SiteExceptionHandler.statusOf(e.code());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (e.retryAfterMs() > 0) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1L, e.retryAfterMs() / 1000L)));
        }
        objectMapper.writeValue(response.getOutputStream(), SiteApiResponse.failure(e.code(),
            e.getMessage(), SiteExceptionHandler.retryable(e.code())));
    }
}
