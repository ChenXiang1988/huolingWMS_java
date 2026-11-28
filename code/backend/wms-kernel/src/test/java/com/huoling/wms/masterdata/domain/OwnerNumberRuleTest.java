package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 货主编号规则：OW 开头、8 至 32 位、字符集 [A-Z0-9]（契约 1.5、1.11）。 */
class OwnerNumberRuleTest {

    @ParameterizedTest
    @ValueSource(strings = {"OW000001", "OW999999", "OW1234567", "OW000000000000000000000000000001"})
    @DisplayName("以 OW 开头、长度在 8 到 32 位之间的大写字母数字串放行")
    void 合法编号(String ownerNo) {
        OwnerNumberRule.requireValid(ownerNo);
        assertThat(OwnerNumberRule.valid(ownerNo)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ow000001",          // 小写
        "OW-00001",          // 含连字符
        "OW 00001",          // 含空格
        "XW000001",          // 前缀不是 OW
        "OW00001",           // 7 位，短于 8 位
        "OW0000000000000000000000000000001" // 33 位，长于 32 位
    })
    @DisplayName("形态不符一律按参数错误拒绝")
    void 非法编号(String ownerNo) {
        assertThatThrownBy(() -> OwnerNumberRule.requireValid(ownerNo))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
        assertThat(OwnerNumberRule.valid(ownerNo)).isFalse();
    }

    @Test
    @DisplayName("缺失与全空格按缺失处理")
    void 缺失编号() {
        for (String ownerNo : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> OwnerNumberRule.requireValid(ownerNo))
                .isInstanceOf(OwnerRuleViolation.class)
                .extracting(e -> ((OwnerRuleViolation) e).code())
                .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
        }
        assertThat(OwnerNumberRule.valid(null)).isFalse();
    }

    @Test
    @DisplayName("序号生成：首个编号 OW000001，序号超过六位自然变长")
    void 序号生成() {
        assertThat(OwnerNumberRule.format(1L)).isEqualTo("OW000001");
        assertThat(OwnerNumberRule.format(999_999L)).isEqualTo("OW999999");
        // 只补零不截断：七位序号变成九位编号，仍在契约的 8 至 32 位内。
        assertThat(OwnerNumberRule.format(1_000_000L)).isEqualTo("OW1000000");
        assertThat(OwnerNumberRule.valid(OwnerNumberRule.format(1_000_000L))).isTrue();
    }

    @Test
    @DisplayName("序号小于一按参数错误拒绝")
    void 非法序号() {
        assertThatThrownBy(() -> OwnerNumberRule.format(0L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }
}
