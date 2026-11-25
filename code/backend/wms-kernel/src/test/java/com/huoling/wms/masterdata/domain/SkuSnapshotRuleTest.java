package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端间一致性规则（specs product-archive-review「版本落后的缓存被标记」、契约 API-010）：
 * 下行摘要只有版本严格大于已应用版本才允许覆盖，缓存数据时间戳超出新鲜度阈值即标记待刷新。
 *
 * <p>期望值来自设计章与契约，不从实现反推：设计「幂等与异步事件」要求「消费方按事件标识去重，
 * 写入缓存并更新数据时间戳」；「一致性边界」要求「单仓缓存版本落后于权威版本时必须标记为待刷新」。
 */
class SkuSnapshotRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-28T10:05:00Z");

    @Test
    @DisplayName("下行版本严格大于已应用版本时才允许覆盖缓存")
    void 版本更大才应用() {
        assertThat(SkuSnapshotRule.shouldApply(2L, 3L)).isTrue();
        // 首次落地缓存：已应用版本为零
        assertThat(SkuSnapshotRule.shouldApply(0L, 1L)).isTrue();
    }

    @Test
    @DisplayName("版本相等或落后一律丢弃，缓存版本不倒退")
    void 版本不前进即丢弃() {
        assertThat(SkuSnapshotRule.shouldApply(3L, 3L)).isFalse();
        assertThat(SkuSnapshotRule.shouldApply(3L, 2L)).isFalse();
        assertThat(SkuSnapshotRule.shouldApply(3L, 1L)).isFalse();
    }

    @Test
    @DisplayName("摘要缺版本号按参数错误拒绝，不当成可应用的消息")
    void 缺版本号拒绝() {
        assertThatThrownBy(() -> SkuSnapshotRule.shouldApply(1L, 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
        assertThatThrownBy(() -> SkuSnapshotRule.shouldApply(1L, -5L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("数据时间戳超过新鲜度阈值标为待刷新，未超过则为新鲜")
    void 按数据时间戳判定待刷新() {
        Duration maxAge = Duration.ofMinutes(5);
        // 阈值边界：恰好等于阈值仍按「未超过」处理，只有严格更旧才标待刷新
        assertThat(SkuSnapshotRule.stale(NOW.minus(maxAge), NOW, maxAge)).isFalse();
        assertThat(SkuSnapshotRule.stale(NOW.minus(maxAge).minusSeconds(1), NOW, maxAge)).isTrue();
    }

    @Test
    @DisplayName("缓存没有数据时间戳时一律视为待刷新，不把缓存伪装成最新结果")
    void 缺数据时间戳视为待刷新() {
        assertThat(SkuSnapshotRule.stale(null, NOW, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("新鲜度阈值非正数按参数错误拒绝，不做默认放行")
    void 阈值非法拒绝() {
        assertThatThrownBy(() -> SkuSnapshotRule.stale(NOW, NOW, Duration.ZERO))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
        assertThatThrownBy(() -> SkuSnapshotRule.stale(NOW, NOW, Duration.ofSeconds(-1)))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
    }
}
