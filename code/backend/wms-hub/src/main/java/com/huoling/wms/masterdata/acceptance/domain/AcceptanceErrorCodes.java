package com.huoling.wms.masterdata.acceptance.domain;

import com.huoling.wms.common.error.ArchiveContractCodes;

/**
 * 受理设施自己会发出的错误码：幂等、参数、限流与依赖不可用。
 *
 * <p>取值引用 {@link ArchiveContractCodes}（common）里的同名常量而不复制字面量：
 * 这些码在商品档案与货主档案两个契约里是同一批取值，两处各写一份字符串必然漂移。
 * 业务专属码（如 {@code RULE_DUPLICATE_CREDIT_CODE}、{@code NOT_FOUND_OWNER}）不在这里，
 * 由业务包在派发受理时给出，机制不猜。
 */
public final class AcceptanceErrorCodes {

    /** 字段缺失、越界或枚举非法（400）。 */
    public static final String PARAM_INVALID = ArchiveContractCodes.PARAM_INVALID;
    /** 同一幂等键的首次请求仍在处理（409，可重试）。 */
    public static final String IDEMPOTENT_IN_PROGRESS = ArchiveContractCodes.IDEMPOTENT_IN_PROGRESS;
    /** 同一幂等键但请求指纹不同（409）。 */
    public static final String IDEMPOTENT_KEY_CONFLICT =
        ArchiveContractCodes.IDEMPOTENT_KEY_CONFLICT;
    /** 端加接口维度超限（429，可重试）。 */
    public static final String RATE_LIMITED = ArchiveContractCodes.RATE_LIMITED;
    /** 受理存储或执行通道不可用（503，可重试）。 */
    public static final String DEPENDENCY_UNAVAILABLE = ArchiveContractCodes.DEPENDENCY_UNAVAILABLE;
    /** 未捕获系统异常（500）。 */
    public static final String INTERNAL_ERROR = ArchiveContractCodes.INTERNAL_ERROR;

    private AcceptanceErrorCodes() {
    }
}
