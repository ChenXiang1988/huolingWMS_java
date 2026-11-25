package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 版本号规则与下行快照去重规则。 */
class SkuArchiveVersionRuleTest {

    @Test
    @DisplayName("版本号每次变更递增一")
    void 版本递增() {
        assertThat(SkuArchiveVersionRule.next(0)).isEqualTo(1);
        assertThat(SkuArchiveVersionRule.next(41)).isEqualTo(42);
    }

    @Test
    @DisplayName("版本号与当前一致才放行")
    void 版本一致放行() {
        assertThatCode(() -> SkuArchiveVersionRule.requireMatches(7, 7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("版本落后返回 CONFLICT_VERSION，且提示当前版本")
    void 版本落后() {
        assertThatThrownBy(() -> SkuArchiveVersionRule.requireMatches(8, 7L))
            .isInstanceOf(SkuRuleViolation.class)
            .hasMessageContaining("8")
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.CONFLICT_VERSION);
    }

    @Test
    @DisplayName("未携带版本号按参数错误拒绝")
    void 缺少版本号() {
        assertThatThrownBy(() -> SkuArchiveVersionRule.requireMatches(8, null))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("落后的下行摘要被丢弃，更新的摘要被应用")
    void 摘要去重() {
        assertThat(SkuSnapshotRule.shouldApply(5, 6)).isTrue();
        assertThat(SkuSnapshotRule.shouldApply(6, 6)).isFalse();
        assertThat(SkuSnapshotRule.shouldApply(6, 5)).isFalse();
    }

    @Test
    @DisplayName("下行摘要缺版本号按参数错误拒绝")
    void 摘要缺版本号() {
        assertThatThrownBy(() -> SkuSnapshotRule.shouldApply(0, 0))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("缓存数据超过新鲜度阈值即待刷新，未缓存也待刷新")
    void 待刷新判定() {
        Instant now = Instant.parse("2026-11-28T10:00:00Z");
        Duration maxAge = Duration.ofMinutes(30);
        assertThat(SkuSnapshotRule.stale(now.minus(Duration.ofMinutes(10)), now, maxAge)).isFalse();
        assertThat(SkuSnapshotRule.stale(now.minus(Duration.ofMinutes(31)), now, maxAge)).isTrue();
        assertThat(SkuSnapshotRule.stale(null, now, maxAge)).isTrue();
    }
}
