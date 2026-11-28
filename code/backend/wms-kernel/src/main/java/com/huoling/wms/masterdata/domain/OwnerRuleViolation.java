package com.huoling.wms.masterdata.domain;

/**
 * 货主档案规则被违反，与 {@link SkuRuleViolation} 同构。只带规则码与说明，不带 HTTP 语义；
 * 接口层按契约错误码表把规则码映射成 HTTP 状态与 {@code retryable}。
 */
public class OwnerRuleViolation extends RuleViolation {

    private final String code;

    public OwnerRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
