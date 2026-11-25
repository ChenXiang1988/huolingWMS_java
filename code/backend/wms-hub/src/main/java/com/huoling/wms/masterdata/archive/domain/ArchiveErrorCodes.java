package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.common.error.ArchiveContractCodes;

/**
 * 商品档案接口的错误码常量，与本次变更契约「错误码表」逐行对应，取值即契约里的符号码。
 *
 * <p>取值只有一处定义：{@link ArchiveContractCodes}（common），Hub 与 Site 侧共用同一份；
 * 这里做别名，保持档案模块内的引用口径，不另抄字符串。
 */
public final class ArchiveErrorCodes {

    /** 字段缺失、越界或枚举非法（400）。 */
    public static final String PARAM_INVALID = ArchiveContractCodes.PARAM_INVALID;
    /** 货主内编码重复（422）。 */
    public static final String RULE_DUPLICATE_CODE = ArchiveContractCodes.RULE_DUPLICATE_CODE;
    /** 货主内条码重复（422）。 */
    public static final String RULE_DUPLICATE_BARCODE = ArchiveContractCodes.RULE_DUPLICATE_BARCODE;
    /** 状态迁移不在守卫表内（409）。 */
    public static final String STATE_INVALID = ArchiveContractCodes.STATE_INVALID;
    /** 版本号落后（409，可重试）。 */
    public static final String CONFLICT_VERSION = ArchiveContractCodes.CONFLICT_VERSION;
    /** 档案不存在或不在数据域内（404）。 */
    public static final String NOT_FOUND_ARCHIVE = ArchiveContractCodes.NOT_FOUND_ARCHIVE;
    /** 货主越权访问他人档案（403）。 */
    public static final String FORBIDDEN_OWNER = ArchiveContractCodes.FORBIDDEN_OWNER;
    /** 单仓越权发起变更申请（403）。 */
    public static final String FORBIDDEN_SITE = ArchiveContractCodes.FORBIDDEN_SITE;
    /** 幂等记录处理中（409，可重试）。 */
    public static final String IDEMPOTENT_IN_PROGRESS = ArchiveContractCodes.IDEMPOTENT_IN_PROGRESS;
    /** 同键异体（409）。 */
    public static final String IDEMPOTENT_KEY_CONFLICT = ArchiveContractCodes.IDEMPOTENT_KEY_CONFLICT;
    /** 端加货主维度超限（429，可重试）。 */
    public static final String RATE_LIMITED = ArchiveContractCodes.RATE_LIMITED;
    /** 下行或缓存依赖不可用（503，可重试）。 */
    public static final String DEPENDENCY_UNAVAILABLE = ArchiveContractCodes.DEPENDENCY_UNAVAILABLE;
    /** 未捕获系统异常（500）。 */
    public static final String INTERNAL_ERROR = ArchiveContractCodes.INTERNAL_ERROR;

    private ArchiveErrorCodes() {
    }
}
