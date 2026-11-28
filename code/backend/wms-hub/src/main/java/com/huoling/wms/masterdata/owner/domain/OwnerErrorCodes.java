package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.common.error.OwnerContractCodes;

/**
 * 货主档案接口的错误码常量，与本次变更契约 1.4 表逐行对应，取值即契约里的符号码。
 *
 * <p>取值只有一处定义：{@link OwnerContractCodes}（common）。这里做别名，保持货主档案模块内的引用口径，
 * 不另抄字符串。
 */
public final class OwnerErrorCodes {

    /** 字段缺失、越界或枚举非法（400）。 */
    public static final String PARAM_INVALID = OwnerContractCodes.PARAM_INVALID;
    /** 统一社会信用代码重复（422）。 */
    public static final String RULE_DUPLICATE_CREDIT_CODE =
        OwnerContractCodes.RULE_DUPLICATE_CREDIT_CODE;
    /** 状态迁移不在守卫表内（409）。 */
    public static final String STATE_INVALID = OwnerContractCodes.STATE_INVALID;
    /** 版本号落后（409，可重试）。 */
    public static final String CONFLICT_VERSION = OwnerContractCodes.CONFLICT_VERSION;
    /** 货主不存在或不在货主域内（404）。 */
    public static final String NOT_FOUND_OWNER = OwnerContractCodes.NOT_FOUND_OWNER;
    /** 端标识不是 HUB、货主域为空、非全域建档（403）。 */
    public static final String FORBIDDEN_OWNER = OwnerContractCodes.FORBIDDEN_OWNER;
    /** 同一幂等键的首次受理仍在处理（409，可重试）。 */
    public static final String IDEMPOTENT_IN_PROGRESS = OwnerContractCodes.IDEMPOTENT_IN_PROGRESS;
    /** 同一幂等键但请求指纹不同（409）。 */
    public static final String IDEMPOTENT_KEY_CONFLICT =
        OwnerContractCodes.IDEMPOTENT_KEY_CONFLICT;
    /** 运营端该接口超过配额（429，可重试）。 */
    public static final String RATE_LIMITED = OwnerContractCodes.RATE_LIMITED;
    /** 受理存储或依赖不可用（503，可重试）。 */
    public static final String DEPENDENCY_UNAVAILABLE = OwnerContractCodes.DEPENDENCY_UNAVAILABLE;
    /** 未捕获系统异常（500）。 */
    public static final String INTERNAL_ERROR = OwnerContractCodes.INTERNAL_ERROR;

    private OwnerErrorCodes() {
    }
}
