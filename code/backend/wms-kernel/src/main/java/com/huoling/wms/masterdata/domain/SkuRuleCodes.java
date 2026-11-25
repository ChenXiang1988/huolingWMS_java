package com.huoling.wms.masterdata.domain;

import com.huoling.wms.common.error.ArchiveContractCodes;

/**
 * Kernel 侧规则码，与本次变更契约错误码表逐字一致。
 *
 * <p>取值只有一处定义：{@link ArchiveContractCodes}（common）。这里做别名，是为了让 kernel 的规则
 * 与 Hub、Site 的响应体引用同一个常量，避免三处各抄一份字符串导致取值漂移。
 */
public final class SkuRuleCodes {

    /** 字段缺失、越界或枚举非法。 */
    public static final String PARAM_INVALID = ArchiveContractCodes.PARAM_INVALID;
    /** 货主内编码重复。 */
    public static final String RULE_DUPLICATE_CODE = ArchiveContractCodes.RULE_DUPLICATE_CODE;
    /** 货主内条码重复。 */
    public static final String RULE_DUPLICATE_BARCODE = ArchiveContractCodes.RULE_DUPLICATE_BARCODE;
    /** 状态迁移不在守卫表内。 */
    public static final String STATE_INVALID = ArchiveContractCodes.STATE_INVALID;
    /** 版本号落后。 */
    public static final String CONFLICT_VERSION = ArchiveContractCodes.CONFLICT_VERSION;

    private SkuRuleCodes() {
    }
}
