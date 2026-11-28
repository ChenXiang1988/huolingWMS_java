package com.huoling.wms.masterdata.owner.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import com.huoling.wms.masterdata.owner.api.OwnerBodySizeFilter;
import com.huoling.wms.masterdata.owner.api.OwnerClientFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 货主档案接口的两道入口过滤器：端白名单与请求体体积上限。
 *
 * <p>只挂在货主档案的两组路径上（{@code /api/hub/owner-archives/*} 与 {@code /api/hub/owners}）：
 * 商品档案的三端接口共用 {@code /api/*} 前缀，全局收紧会让 {@code /api/owner}、{@code /api/site}
 * 的既有调用一起 403。顺序排在既有的 {@code archiveAuthenticationFilter} 之后，
 * 保证「令牌无效」仍是 401，只有「令牌有效但端不对」才是 403 {@code FORBIDDEN_OWNER}。
 *
 * <p>体积上限只作用于写接口所在的前缀（{@code /api/hub/owner-archives/*}）：
 * 选择器 {@code /api/hub/owners} 是查询接口，没有请求体，不需要这条门。
 */
@Configuration
public class OwnerWebConfig {

    @Bean
    public FilterRegistrationBean<OwnerClientFilter> ownerClientFilter(
            JwtTokenService tokenService, ObjectMapper objectMapper) {
        FilterRegistrationBean<OwnerClientFilter> registration =
            new FilterRegistrationBean<>(new OwnerClientFilter(tokenService, objectMapper));
        registration.setName("ownerClientFilter");
        registration.addUrlPatterns("/api/hub/owner-archives/*", "/api/hub/owners");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 22);
        return registration;
    }

    /**
     * 请求体 16 KB 上限的入口拦截（契约 1.1「请求边界」）。
     *
     * <p>顺序排在端白名单之后（+23）：先判令牌与端，再判体积——否则一个未授权的大载荷
     * 会先拿到 400，把 403 的语义盖掉。两者都在 {@code @RequestBody} 解析之前，
     * 超限请求不会占用反序列化的内存与 GC。
     */
    @Bean
    public FilterRegistrationBean<OwnerBodySizeFilter> ownerBodySizeFilter(ObjectMapper objectMapper) {
        FilterRegistrationBean<OwnerBodySizeFilter> registration =
            new FilterRegistrationBean<>(new OwnerBodySizeFilter(objectMapper));
        registration.setName("ownerBodySizeFilter");
        registration.addUrlPatterns("/api/hub/owner-archives/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 23);
        return registration;
    }
}
