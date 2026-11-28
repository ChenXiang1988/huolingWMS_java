package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 版本规则：每次成功写入递增一，落后即冲突（OI-09、契约 1.5）。 */
class OwnerVersionRuleTest {

    @Test
    @DisplayName("版本递增一只前进一格")
    void 递增() {
        assertThat(OwnerVersionRule.next(0L)).isEqualTo(1L);
        assertThat(OwnerVersionRule.next(1L)).isEqualTo(2L);
    }

    @Test
    @DisplayName("负数版本按参数错误拒绝")
    void 负数版本() {
        assertThatThrownBy(() -> OwnerVersionRule.next(-1L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("版本匹配放行")
    void 版本匹配() {
        assertThatCode(() -> OwnerVersionRule.requireMatches(3L, 3L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("版本落后按可重试的版本冲突拒绝")
    void 版本冲突() {
        assertThatThrownBy(() -> OwnerVersionRule.requireMatches(3L, 2L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.CONFLICT_VERSION);
    }

    @Test
    @DisplayName("未携带版本或版本为负按参数错误拒绝")
    void 版本缺失() {
        assertThatThrownBy(() -> OwnerVersionRule.requireMatches(0L, null))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
        assertThatThrownBy(() -> OwnerVersionRule.requireMatches(0L, -1L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }
}
