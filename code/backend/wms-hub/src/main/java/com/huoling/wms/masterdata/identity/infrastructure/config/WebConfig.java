package com.huoling.wms.masterdata.identity.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.identity.api.JwtAuthenticationFilter;
import com.huoling.wms.masterdata.identity.api.PermissionInterceptor;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.OwnerSessionGuard;
import com.huoling.wms.masterdata.identity.domain.TokenBlacklist;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * 鉴权链路的注册顺序：过滤器（第一道门）先于拦截器（第二道门）执行。
 *
 * <p>两组前缀各注册一个过滤器实例：
 * <ul>
 *   <li>{@code /hub/api/*}：运营端接口，只接受运营端令牌</li>
 *   <li>{@code /api/*}：商品档案的三端接口（{@code /api/owner}、{@code /api/hub}、{@code /api/site}），
 *       接受三端令牌；具体某个接口属于哪个端、能看哪些数据，由控制器解析数据域后校验（INV-19）</li>
 * </ul>
 * 免鉴权入口只由 {@link JwtAuthenticationFilter#AUTH_FREE_PATHS} 声明，均在 {@code /hub/api} 下。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    public WebConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    /**
     * 第一道门挂在 {@code /hub/api/*}：验签、过期、{@code jti} 黑名单、端标识。
     * 不走 {@code @Component} 自动注册，避免被容器以 {@code /*} 再注册一遍而误拦健康检查与文档。
     */
    /**
     * 第一道门挂在 {@code /hub/api/*}：验签、过期、{@code jti} 黑名单、端标识、货主端会话可用性。
     * 不走 {@code @Component} 自动注册，避免被容器以 {@code /*} 再注册一遍而误拦健康检查与文档。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilter(
            JwtTokenService tokenService, TokenBlacklist blacklist, ObjectMapper objectMapper,
            OwnerSessionGuard ownerSessions) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
            new FilterRegistrationBean<>(new JwtAuthenticationFilter(tokenService, blacklist,
                objectMapper, ownerSessions));
        registration.setName("jwtAuthenticationFilter");
        registration.addUrlPatterns("/hub/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /** 商品档案三端接口的第一道门：同一套验签、过期、黑名单与会话可用性，端白名单放宽到三端。 */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> archiveAuthenticationFilter(
            JwtTokenService tokenService, TokenBlacklist blacklist, ObjectMapper objectMapper,
            OwnerSessionGuard ownerSessions) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
            new FilterRegistrationBean<>(new JwtAuthenticationFilter(tokenService, blacklist, objectMapper,
                Set.of(ClientType.HUB, ClientType.OWNER, ClientType.SITE), ownerSessions));
        registration.setName("archiveAuthenticationFilter");
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 21);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
            .addPathPatterns("/hub/api/**")
            .excludePathPatterns(JwtAuthenticationFilter.AUTH_FREE_PATHS.toArray(new String[0]));
        // 第二道门同样覆盖商品档案三端接口：权限点注解在方法上，缺注解的接口在启动期就会被拦住。
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/**");
    }
}
