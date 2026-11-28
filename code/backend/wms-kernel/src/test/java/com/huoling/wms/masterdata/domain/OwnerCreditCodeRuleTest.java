package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 信用代码规则：18 位、大写、排除 I/O/S/V/Z，不校验校验位（契约 1.8）。 */
class OwnerCreditCodeRuleTest {

    @ParameterizedTest
    @ValueSource(strings = {"91350100MA2Y1234XY", "91440300MA5FT8GH2K", "11000000000000000X"})
    @DisplayName("18 位大写字母数字放行，不校验校验位")
    void 合法信用代码(String creditCode) {
        OwnerCreditCodeRule.requireValid(creditCode);
        assertThat(OwnerCreditCodeRule.valid(creditCode)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "9135O100MA2Y1234XY",  // 含 O（排除字符）
        "9135I100MA2Y1234XY",  // 含 I
        "9135S100MA2Y1234XY",  // 含 S
        "9135V100MA2Y1234XY",  // 含 V
        "9135Z100MA2Y1234XY",  // 含 Z
        "91350100ma2y1234xy",  // 小写，不静默转写
        "91350100MA2Y1234X",   // 17 位
        "91350100MA2Y1234XYZ", // 19 位
        "91350100MA2Y1234 X"   // 含空格
    })
    @DisplayName("排除字符、小写与长度不符一律拒绝")
    void 非法信用代码(String creditCode) {
        assertThatThrownBy(() -> OwnerCreditCodeRule.requireValid(creditCode))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
        assertThat(OwnerCreditCodeRule.valid(creditCode)).isFalse();
    }

    @Test
    @DisplayName("缺失与全空格按缺失处理")
    void 缺失信用代码() {
        for (String creditCode : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> OwnerCreditCodeRule.requireValid(creditCode))
                .isInstanceOf(OwnerRuleViolation.class)
                .extracting(e -> ((OwnerRuleViolation) e).code())
                .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
        }
        assertThat(OwnerCreditCodeRule.valid(null)).isFalse();
    }
}
