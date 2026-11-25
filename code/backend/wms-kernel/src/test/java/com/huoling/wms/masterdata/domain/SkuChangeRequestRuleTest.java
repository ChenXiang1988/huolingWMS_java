package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 变更申请规则：同一档案仅一条待审核、只有待审核可审、驳回必须给原因。 */
class SkuChangeRequestRuleTest {

    @Test
    @DisplayName("同一档案已有待审核申请时拒绝再次发起")
    void 仅一条待审核() {
        assertThatThrownBy(() -> SkuChangeRequestRule.requireNoPending(true))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.STATE_INVALID);
        assertThatCode(() -> SkuChangeRequestRule.requireNoPending(false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("只有待审核申请可被审核，已审核过的拒绝")
    void 只有待审核可审() {
        assertThatCode(() ->
            SkuChangeRequestRule.requireReviewable(SkuChangeRequestStatus.PENDING, true, null))
            .doesNotThrowAnyException();
        for (SkuChangeRequestStatus status : new SkuChangeRequestStatus[]{
            SkuChangeRequestStatus.APPROVED, SkuChangeRequestStatus.REJECTED}) {
            assertThatThrownBy(() -> SkuChangeRequestRule.requireReviewable(status, true, null))
                .as("状态 %s 不可再审", status)
                .isInstanceOf(SkuRuleViolation.class)
                .extracting(e -> ((SkuRuleViolation) e).code())
                .isEqualTo(SkuRuleCodes.STATE_INVALID);
        }
    }

    @Test
    @DisplayName("驳回未填原因拒绝审核动作")
    void 驳回必须给原因() {
        assertThatThrownBy(() ->
            SkuChangeRequestRule.requireReviewable(SkuChangeRequestStatus.PENDING, false, " "))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
        assertThatCode(() ->
            SkuChangeRequestRule.requireReviewable(SkuChangeRequestStatus.PENDING, false, "规格不符"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("审核结果落入通过或驳回两态")
    void 审核结果() {
        assertThat(SkuChangeRequestRule.target(true)).isEqualTo(SkuChangeRequestStatus.APPROVED);
        assertThat(SkuChangeRequestRule.target(false)).isEqualTo(SkuChangeRequestStatus.REJECTED);
    }

    @Test
    @DisplayName("变更字段是闭集，闭集外按参数错误拒绝")
    void 变更字段闭集() {
        assertThat(SkuChangeableField.parse("specification")).isEqualTo(SkuChangeableField.SPECIFICATION);
        assertThat(SkuChangeableField.parse("NET_WEIGHT").numeric()).isTrue();
        assertThatThrownBy(() -> SkuChangeableField.parse("ownerId"))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
        assertThatThrownBy(() -> SkuChangeableField.parse(null))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
    }
}
