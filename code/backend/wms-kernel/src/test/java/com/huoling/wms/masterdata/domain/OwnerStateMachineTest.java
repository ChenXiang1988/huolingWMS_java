package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主档案状态机：只允许 可用→停用 与 停用→可用 两条状态迁移（tasks 1.1 的验证口径）。
 */
class OwnerStateMachineTest {

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
        "ACTIVE,   EDIT,    ACTIVE",
        "ACTIVE,   DISABLE, DISABLED",
        "DISABLED, ENABLE,  ACTIVE"
    })
    @DisplayName("合法迁移逐条放行")
    void 合法迁移(OwnerStatus from, OwnerAction action, OwnerStatus expected) {
        assertThat(OwnerStateMachine.transit(from, action)).isEqualTo(expected);
    }

    @Test
    @DisplayName("建档从无档案进入可用，编号与状态都由系统置初值")
    void 建档进入可用() {
        assertThat(OwnerStateMachine.transit(null, OwnerAction.CREATE)).isEqualTo(OwnerStatus.ACTIVE);
    }

    @Test
    @DisplayName("停用态改档被拒（须先启用），规则码 STATE_INVALID")
    void 停用态不可改档() {
        assertThatThrownBy(() -> OwnerStateMachine.transit(OwnerStatus.DISABLED, OwnerAction.EDIT))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("重复停用被拒")
    void 重复停用被拒() {
        assertThatThrownBy(() -> OwnerStateMachine.transit(OwnerStatus.DISABLED, OwnerAction.DISABLE))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("对可用货主再启用被拒（没有自动恢复路径，也没有第二次启用）")
    void 对可用再启用被拒() {
        assertThatThrownBy(() -> OwnerStateMachine.transit(OwnerStatus.ACTIVE, OwnerAction.ENABLE))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("档案已存在时不允许再次建档")
    void 重复建档被拒() {
        for (OwnerStatus status : OwnerStatus.values()) {
            assertThatThrownBy(() -> OwnerStateMachine.transit(status, OwnerAction.CREATE))
                .as("状态 %s 下建档必须被拒", status)
                .isInstanceOf(OwnerRuleViolation.class)
                .extracting(e -> ((OwnerRuleViolation) e).code())
                .isEqualTo(OwnerRuleCodes.STATE_INVALID);
        }
    }

    @Test
    @DisplayName("无档案时除建档外的动作一律拒绝，不做默认放行")
    void 无档案只允许建档() {
        for (OwnerAction action : new OwnerAction[]{
            OwnerAction.EDIT, OwnerAction.DISABLE, OwnerAction.ENABLE}) {
            assertThatThrownBy(() -> OwnerStateMachine.transit(null, action))
                .as("无档案动作 %s 必须被拒绝", action)
                .isInstanceOf(OwnerRuleViolation.class)
                .extracting(e -> ((OwnerRuleViolation) e).code())
                .isEqualTo(OwnerRuleCodes.STATE_INVALID);
        }
    }

    @ParameterizedTest
    @EnumSource(OwnerStatus.class)
    @DisplayName("每种状态都给出可判定的是否允许，与允许集合一致")
    void 允许集合可判定(OwnerStatus status) {
        for (OwnerAction action : OwnerAction.values()) {
            assertThat(OwnerStateMachine.allows(status, action))
                .as("%s + %s", status, action)
                .isEqualTo(OwnerStateMachine.allowedActions(status).contains(action));
        }
    }

    @Test
    @DisplayName("允许集合：可用只可改档与停用，停用只可启用")
    void 允许集合内容() {
        assertThat(OwnerStateMachine.allowedActions(OwnerStatus.ACTIVE))
            .containsExactlyInAnyOrder(OwnerAction.EDIT, OwnerAction.DISABLE);
        assertThat(OwnerStateMachine.allowedActions(OwnerStatus.DISABLED))
            .containsExactly(OwnerAction.ENABLE);
    }

    @Test
    @DisplayName("缺动作按参数错误拒绝")
    void 空入参() {
        assertThatThrownBy(() -> OwnerStateMachine.transit(OwnerStatus.ACTIVE, null))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
        assertThat(OwnerStateMachine.allows(null, null)).isFalse();
    }
}
