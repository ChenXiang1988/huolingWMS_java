package com.huoling.wms.masterdata.domain;

/**
 * 商品档案规则被违反。只带规则码与说明，不带 HTTP 语义；
 * 接口层按契约错误码表把规则码映射成 HTTP 状态与 {@code retryable}。
 */
public class SkuRuleViolation extends RuntimeException {

    private final String code;

    public SkuRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
