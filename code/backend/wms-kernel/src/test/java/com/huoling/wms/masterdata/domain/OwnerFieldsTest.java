package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 四项必填与字符集（契约 1.5、OI-03）。 */
class OwnerFieldsTest {

    private static final String CREDIT_CODE = "91350100MA2Y1234XY";

    @Test
    @DisplayName("四项齐备放行，名称允许重复不做本地去重")
    void 四项齐备() {
        assertThatCode(() -> fields("华东仓储有限公司", "张三", "021-62345678").requireComplete())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("名称缺失逐项拒绝，且规则码为 PARAM_INVALID")
    void 名称必填() {
        assertInvalid(fields(null, "张三", "021-62345678"), "ownerName");
        assertInvalid(fields("", "张三", "021-62345678"), "ownerName");
        assertInvalid(fields("   ", "张三", "021-62345678"), "ownerName");
    }

    @Test
    @DisplayName("联系人缺失逐项拒绝")
    void 联系人必填() {
        assertInvalid(fields("华东仓储有限公司", null, "021-62345678"), "contactName");
        assertInvalid(fields("华东仓储有限公司", "  ", "021-62345678"), "contactName");
    }

    @Test
    @DisplayName("联系电话缺失逐项拒绝")
    void 电话必填() {
        assertInvalid(fields("华东仓储有限公司", "张三", null), "contactPhone");
        assertInvalid(fields("华东仓储有限公司", "张三", "  "), "contactPhone");
    }

    @Test
    @DisplayName("信用代码缺失逐项拒绝")
    void 信用代码必填() {
        assertInvalid(new OwnerFields("华东仓储有限公司", "张三", "021-62345678", null),
            "creditCode");
        assertInvalid(new OwnerFields("华东仓储有限公司", "张三", "021-62345678", ""),
            "creditCode");
    }

    @Test
    @DisplayName("长度上限逐项拦截：名称 128、联系人 64、电话 32")
    void 长度上限() {
        assertInvalid(fields("名".repeat(129), "张三", "021-62345678"), "ownerName");
        assertInvalid(fields("华东仓储有限公司", "名".repeat(65), "021-62345678"), "contactName");
        assertInvalid(fields("华东仓储有限公司", "张三", "1".repeat(33)), "contactPhone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"021-62345678", "021 6234 5678", "+86(21)62345678", "123456"})
    @DisplayName("电话只校验字符集与长度，不做号段校验")
    void 电话字符集放行(String phone) {
        assertThatCode(() -> fields("华东仓储有限公司", "张三", phone).requireComplete())
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "021-ABCDEFG", "021_62345678", "021#62345678"})
    @DisplayName("电话过短或含字符集外字符被拒")
    void 电话字符集拒绝(String phone) {
        assertInvalid(fields("华东仓储有限公司", "张三", phone), "contactPhone");
    }

    @Test
    @DisplayName("停用与启用原因：必填且不超过 200 字")
    void 原因规则() {
        assertThatCode(() -> OwnerFields.requireReason("合同到期"))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> OwnerFields.requireReason(null))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
        assertThatThrownBy(() -> OwnerFields.requireReason("   "))
            .isInstanceOf(OwnerRuleViolation.class);
        assertThatThrownBy(() -> OwnerFields.requireReason("原".repeat(201)))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }

    private static OwnerFields fields(String ownerName, String contactName, String contactPhone) {
        return new OwnerFields(ownerName, contactName, contactPhone, CREDIT_CODE);
    }

    private static void assertInvalid(OwnerFields fields, String field) {
        assertThatThrownBy(fields::requireComplete)
            .as("字段 %s 缺失或越界必须被拒", field)
            .isInstanceOf(OwnerRuleViolation.class)
            .hasMessageContaining(field)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }
}
