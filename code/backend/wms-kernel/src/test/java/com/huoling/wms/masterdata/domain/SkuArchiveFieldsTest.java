package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 字段分层与必填校验：五项必填缺一即拒绝，包装换算率联动。 */
class SkuArchiveFieldsTest {

    private static SkuArchiveFields complete() {
        return new SkuArchiveFields("矿泉水 550ml", "OWNER-SKU-001", "6901234567890", "瓶",
            "常温", "饮料", "550ml/瓶", "自有品牌", "EAN13", null, null,
            new BigDecimal("0.55"), new BigDecimal("0.60"), new BigDecimal("0.0006"),
            new BigDecimal("6.5"), new BigDecimal("6.5"), new BigDecimal("22.0"),
            false, false, false, true, false, 365, null, "常温避光");
    }

    @Test
    @DisplayName("必填齐备时通过")
    void 必填齐备() {
        assertThatCode(() -> complete().requireComplete()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("五项必填任一为空即拒绝，指出缺失字段")
    void 必填缺失() {
        SkuArchiveFields noName = new SkuArchiveFields(null, "OWNER-SKU-001", "6901234567890", "瓶",
            "常温", null, null, null, null, null, null, null, null, null, null, null, null,
            false, false, false, false, false, null, null, null);
        assertThatThrownBy(noName::requireComplete)
            .isInstanceOf(SkuRuleViolation.class)
            .hasMessageContaining("name")
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);

        SkuArchiveFields noUnit = new SkuArchiveFields("矿泉水", "OWNER-SKU-001", "6901234567890", " ",
            "常温", null, null, null, null, null, null, null, null, null, null, null, null,
            false, false, false, false, false, null, null, null);
        assertThatThrownBy(noUnit::requireComplete).hasMessageContaining("baseUnit");

        SkuArchiveFields noCondition = new SkuArchiveFields("矿泉水", "OWNER-SKU-001", "6901234567890", "瓶",
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            false, false, false, false, false, null, null, null);
        assertThatThrownBy(noCondition::requireComplete).hasMessageContaining("storageCondition");
    }

    @Test
    @DisplayName("有包装规格时包装换算率必填")
    void 包装换算率联动() {
        SkuArchiveFields fields = new SkuArchiveFields("矿泉水", "OWNER-SKU-001", "6901234567890", "瓶",
            "常温", null, null, null, null, "24瓶/箱", null, null, null, null, null, null, null,
            false, false, false, false, false, null, null, null);
        assertThatThrownBy(fields::requireComplete)
            .isInstanceOf(SkuRuleViolation.class)
            .hasMessageContaining("packageConversionRate");
    }

    @Test
    @DisplayName("长度越界按参数错误拒绝")
    void 长度越界() {
        String longName = "名".repeat(129);
        SkuArchiveFields fields = new SkuArchiveFields(longName, "OWNER-SKU-001", "6901234567890",
            "瓶", "常温", null, null, null, null, null, null, null, null, null, null, null, null,
            false, false, false, false, false, null, null, null);
        assertThatThrownBy(fields::requireComplete).hasMessageContaining("name");
    }

    @Test
    @DisplayName("变更目标值按定点数解析，非数字按参数错误拒绝")
    void 变更取值() {
        SkuArchiveFields changed = complete().withChange(SkuChangeableField.NET_WEIGHT, "1.25");
        assertThat(changed.netWeight()).isEqualByComparingTo("1.25");
        assertThat(changed.name()).isEqualTo("矿泉水 550ml");

        assertThatThrownBy(() -> complete().withChange(SkuChangeableField.VOLUME, "1.2.3"))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
        assertThatThrownBy(() -> complete().withChange(SkuChangeableField.NAME, "  "))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);
    }
}
