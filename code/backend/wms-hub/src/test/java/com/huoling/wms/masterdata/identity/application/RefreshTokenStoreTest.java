package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.infrastructure.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登出黑名单的两个边界：读失败按未命中处理（契约 API-003「降级时黑名单写失败以令牌有效期兜底」），
 * 写失败照常抛出、由统一出口映射为 503 {@code COMMON_9003}。
 */
class RefreshTokenStoreTest {

    private StringRedisTemplate redis;
    private RefreshTokenStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        JwtProperties properties = new JwtProperties();
        properties.setSecret("local-dev-only-secret-key-please-change-32bytes");
        store = new RefreshTokenStore(redis, properties);
    }

    @Test
    @DisplayName("命中与未命中：按 Redis 键值判定")
    void 命中判定() {
        when(redis.hasKey("hub:jti:blacklist:jti-1")).thenReturn(true);
        when(redis.hasKey("hub:jti:blacklist:jti-2")).thenReturn(false);
        assertThat(store.isBlacklisted("jti-1")).isTrue();
        assertThat(store.isBlacklisted("jti-2")).isFalse();
        assertThat(store.isBlacklisted(null)).isFalse();
    }

    @Test
    @DisplayName("存储读失败：按未命中处理，不把 Redis 抖动放大成全站 401")
    void 读失败降级放行() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        assertThat(store.isBlacklisted("jti-1")).isFalse();
    }

    @Test
    @DisplayName("存储写失败：仍抛出，由统一出口返回 503 COMMON_9003")
    void 写失败仍抛出() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("redis down"));
        assertThatThrownBy(() -> store.blacklist("jti-1", 60))
            .isInstanceOf(RedisConnectionFailureException.class);
    }
}
