package com.huoling.wms.masterdata.domain;

import com.huoling.wms.common.error.OwnerContractCodes;

/**
 * Kernel 侧货主档案规则码，与本次变更契约错误码表逐字一致。
 *
 * <p>取值只有一处定义：{@link OwnerContractCodes}（common）。这里做别名，是为了让 kernel 的规则
 * 与 Hub 的响应体引用同一个常量，避免两处各抄一份字符串导致取值漂移。
 */
public final class OwnerRuleCodes {

    /** 字段缺失、越界或枚举非法。 */
    public static final String PARAM_INVALID = OwnerContractCodes.PARAM_INVALID;
    /** 统一社会信用代码重复。 */
    public static final String RULE_DUPLICATE_CREDIT_CODE =
        OwnerContractCodes.RULE_DUPLICATE_CREDIT_CODE;
    /** 状态迁移不在守卫表内。 */
    public static final String STATE_INVALID = OwnerContractCodes.STATE_INVALID;
    /** 版本号落后。 */
    public static final String CONFLICT_VERSION = OwnerContractCodes.CONFLICT_VERSION;
    /** 货主不存在或不在货主域内。 */
    public static final String NOT_FOUND_OWNER = OwnerContractCodes.NOT_FOUND_OWNER;
    /** 端标识不是 HUB、货主域为空、非全域建档。 */
    public static final String FORBIDDEN_OWNER = OwnerContractCodes.FORBIDDEN_OWNER;

    private OwnerRuleCodes() {
    }
}
