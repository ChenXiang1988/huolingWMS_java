package com.huoling.wms.masterdata.domain;

/**
 * 业务规则或业务前置条件被违反：只带契约错误码与说明，不带 HTTP 语义。
 *
 * <p>为什么抽成公共父类：受理机制（{@code masterdata.acceptance}）要统一把「业务失败」落成
 * 受理记录的 {@code FAILED}，如果它逐个 import 业务异常类型，机制就会反向依赖各业务包。
 * 做成 {@link RuntimeException} 的子类而不是接口，是因为 Java 的 {@code catch} 只接受 Throwable 类型；
 * 继承关系不变的部分由各业务保留：{@link SkuRuleViolation}、{@link OwnerRuleViolation} 是内核规则异常，
 * 各业务包里的应用异常（{@code ArchiveException}、{@code AcceptanceException}、货主档案的
 * {@code OwnerContractException}）也继承本类，取值口径与响应形态都不变。
 *
 * <p>HTTP 状态与 {@code retryable} 仍由接口层的映射表按 {@link #code()} 给出。
 */
public abstract class RuleViolation extends RuntimeException {

    protected RuleViolation(String message) {
        super(message);
    }

    /** 契约错误码，取值来自各业务的契约错误码表。 */
    public abstract String code();
}
