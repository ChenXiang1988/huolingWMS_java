package com.huoling.wms.masterdata.identity.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 运营端安全基线：无状态、关闭表单登录与 CSRF，健康检查与认证入口放行。
 * 令牌与权限校验由 design「鉴权链路」的两道门完成，不依赖这里的默认配置：
 * 第一道 {@code JwtAuthenticationFilter}（验签、过期、黑名单、端标识），第二道 {@code PermissionInterceptor}（权限点）。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 鉴权由 JwtAuthenticationFilter + PermissionInterceptor 两道门完成，
            // 这样 401 与 403 的语义与契约一致；Security 只负责无状态与关闭默认登录页。
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
