package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机守卫：legal 迁移必须放行，非法迁移必须被明确拒绝（tasks 1.1 的验证口径）。
 */
class SkuArchiveStateMachineTest {

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
        "DRAFT,    SUBMIT,  PENDING",
        "REJECTED, SUBMIT,  PENDING",
        "DISABLED, SUBMIT,  PENDING",
        "PENDING,  APPROVE, ACTIVE",
        "PENDING,  REJECT,  REJECTED",
        "ACTIVE,   DISABLE, DISABLED",
        "DRAFT,    EDIT,    DRAFT",
        "REJECTED, EDIT,    REJECTED"
    })
    @DisplayName("合法迁移逐条放行")
    void 合法迁移(SkuArchiveStatus from, SkuArchiveAction action, SkuArchiveStatus expected) {
        assertThat(SkuArchiveStateMachine.transit(from, action)).isEqualTo(expected);
    }

    @Test
    @DisplayName("草稿到待审核到启用到停用到待审核的整条链可走通")
    void 全链路() {
        SkuArchiveStatus status = SkuArchiveStatus.DRAFT;
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.SUBMIT);
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.APPROVE);
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.DISABLE);
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.SUBMIT);
        assertThat(status).isEqualTo(SkuArchiveStatus.PENDING);
    }

    @Test
    @DisplayName("驳回后重提回到待审核，驳回原因由留痕保留")
    void 驳回后重提() {
        SkuArchiveStatus rejected =
            SkuArchiveStateMachine.transit(SkuArchiveStatus.PENDING, SkuArchiveAction.REJECT);
        assertThat(rejected).isEqualTo(SkuArchiveStatus.REJECTED);
        assertThat(SkuArchiveStateMachine.transit(rejected, SkuArchiveAction.SUBMIT))
            .isEqualTo(SkuArchiveStatus.PENDING);
    }

    @Test
    @DisplayName("草稿不能直接审核通过，跳过提交必须被拒绝")
    void 草稿不能直接启用() {
        assertThatThrownBy(() ->
            SkuArchiveStateMachine.transit(SkuArchiveStatus.DRAFT, SkuArchiveAction.APPROVE))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("启用态不能再提交、不能驳回，也不能被编辑覆盖")
    void 启用态不可回退() {
        for (SkuArchiveAction action : new SkuArchiveAction[]{
            SkuArchiveAction.SUBMIT, SkuArchiveAction.APPROVE,
            SkuArchiveAction.REJECT, SkuArchiveAction.EDIT}) {
            assertThatThrownBy(() -> SkuArchiveStateMachine.transit(SkuArchiveStatus.ACTIVE, action))
                .as("启用态动作 %s 必须被拒绝", action)
                .isInstanceOf(SkuRuleViolation.class)
                .extracting(e -> ((SkuRuleViolation) e).code())
                .isEqualTo(SkuRuleCodes.STATE_INVALID);
        }
    }

    @Test
    @DisplayName("待审核态不可编辑、不可停用")
    void 待审核态只能审核() {
        assertThatThrownBy(() ->
            SkuArchiveStateMachine.transit(SkuArchiveStatus.PENDING, SkuArchiveAction.EDIT))
            .isInstanceOf(SkuRuleViolation.class);
        assertThatThrownBy(() ->
            SkuArchiveStateMachine.transit(SkuArchiveStatus.PENDING, SkuArchiveAction.DISABLE))
            .isInstanceOf(SkuRuleViolation.class);
    }

    @Test
    @DisplayName("停用态只能提交恢复，不能直接启用")
    void 停用态只可提交恢复() {
        assertThatThrownBy(() ->
            SkuArchiveStateMachine.transit(SkuArchiveStatus.DISABLED, SkuArchiveAction.APPROVE))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.STATE_INVALID);
    }

    @ParameterizedTest
    @EnumSource(SkuArchiveStatus.class)
    @DisplayName("每种状态都至少给出可判定的是否允许，不做默认放行")
    void 允许集合可判定(SkuArchiveStatus status) {
        for (SkuArchiveAction action : SkuArchiveAction.values()) {
            boolean allowed = SkuArchiveStateMachine.allows(status, action);
            boolean inSet = SkuArchiveStateMachine.allowedActions(status).contains(action);
            assertThat(allowed).as("%s + %s", status, action).isEqualTo(inSet);
        }
    }

    @Test
    @DisplayName("缺状态或动作按参数错误拒绝")
    void 空入参() {
        assertThatThrownBy(() -> SkuArchiveStateMachine.transit(null, SkuArchiveAction.SUBMIT))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
        assertThatThrownBy(() -> SkuArchiveStateMachine.transit(SkuArchiveStatus.DRAFT, null))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
    }
}
