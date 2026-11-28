package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.masterdata.domain.OwnerFields;

import java.util.List;

/**
 * 入口字段级错误：携带契约 1.1 的 {@code details} 明细（{@code field} + {@code reason}）。
 *
 * <p>为什么单独一类：{@link OwnerContractException} 只有错误码与人话，契约 1.1 要求字段级错误
 * 「按 {@code details[].field} 定位到控件」，{@code field} 与契约字段同名。入口层把
 * {@link OwnerFields.Issue} 原样带出来，异常处理器再拼成响应体，不在处理器里猜字段名。
 *
 * <p>只用于同步入口校验（{@code PARAM_INVALID}）：受理后的失败不带 {@code details}，
 * 结果经 API-027 的 {@code errorCode} 返回。
 */
public class OwnerParamException extends OwnerContractException {

    private final List<OwnerFields.Issue> issues;

    public OwnerParamException(List<OwnerFields.Issue> issues, String message) {
        super(OwnerErrorCodes.PARAM_INVALID, message);
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 单字段的一条明细。 */
    public static OwnerParamException of(String field, String reason) {
        return new OwnerParamException(List.of(new OwnerFields.Issue(field, reason)), reason);
    }

    /** 逐字段明细；{@code details} 数组按此输出。 */
    public List<OwnerFields.Issue> issues() {
        return issues;
    }
}
