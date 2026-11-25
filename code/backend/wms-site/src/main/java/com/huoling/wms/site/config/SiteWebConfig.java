package com.huoling.wms.site.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.site.api.SiteTokenFilter;
import com.huoling.wms.site.api.SiteTokenVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 单仓端鉴权链路的注册：{@code /api/*} 全部经过令牌过滤器。
 *
 * <p>不在过滤器上做路径白名单式的放行：{@code /api/site/**} 之外的接口这里一个都没有，
 * 只放行健康检查与文档（过滤器内部排除），避免「新加接口忘了加鉴权」。
 */
@Configuration
public class SiteWebConfig {

    @Bean
    public FilterRegistrationBean<SiteTokenFilter> siteTokenFilter(SiteTokenVerifier verifier,
                                                                  ObjectMapper objectMapper) {
        FilterRegistrationBean<SiteTokenFilter> registration =
            new FilterRegistrationBean<>(new SiteTokenFilter(verifier, objectMapper));
        registration.setName("siteTokenFilter");
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
