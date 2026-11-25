package com.huoling.wms.common.error;

/**
 * 商品档案契约错误码，逐行对应本次变更契约章「错误码表」，取值即契约里的符号码。
 *
 * <p>放 common 而不是各单元：Hub 与 Site 都用同一份取值，两边各抄一份必然漂移；
 * 这里只有常量，不含任何校验逻辑——不变量仍在 kernel 定义（01「不变量只能在 kernel 定义」）。
 *
 * <p>不新增契约外错误码：本表没有的码不允许在代码里出现（契约「鲁棒性约定」）。
 */
public final class ArchiveContractCodes {

    /** 字段缺失、越界或枚举非法（400）。 */
    public static final String PARAM_INVALID = "PARAM_INVALID";
    /** 货主内编码重复（422）。 */
    public static final String RULE_DUPLICATE_CODE = "RULE_DUPLICATE_CODE";
    /** 货主内条码重复（422）。 */
    public static final String RULE_DUPLICATE_BARCODE = "RULE_DUPLICATE_BARCODE";
    /** 状态迁移不在守卫表内（409）。 */
    public static final String STATE_INVALID = "STATE_INVALID";
    /** 版本号落后（409，可重试）。 */
    public static final String CONFLICT_VERSION = "CONFLICT_VERSION";
    /** 档案不存在或不在数据域内（404）。 */
    public static final String NOT_FOUND_ARCHIVE = "NOT_FOUND_ARCHIVE";
    /** 货主越权访问他人档案（403）。 */
    public static final String FORBIDDEN_OWNER = "FORBIDDEN_OWNER";
    /** 单仓越权发起变更申请（403）。 */
    public static final String FORBIDDEN_SITE = "FORBIDDEN_SITE";
    /** 幂等记录处理中（409，可重试）。 */
    public static final String IDEMPOTENT_IN_PROGRESS = "IDEMPOTENT_IN_PROGRESS";
    /** 同键异体（409）。 */
    public static final String IDEMPOTENT_KEY_CONFLICT = "IDEMPOTENT_KEY_CONFLICT";
    /** 端加货主维度超限（429，可重试）。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** 下行或缓存依赖不可用（503，可重试）。 */
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";
    /** 未捕获系统异常（500）。 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ArchiveContractCodes() {
    }

    /** 是否属于契约错误码表；用于拒绝契约外取值进入响应体。 */
    public static boolean known(String code) {
        if (code == null) {
            return false;
        }
        return switch (code) {
            case PARAM_INVALID, RULE_DUPLICATE_CODE, RULE_DUPLICATE_BARCODE, STATE_INVALID,
                 CONFLICT_VERSION, NOT_FOUND_ARCHIVE, FORBIDDEN_OWNER, FORBIDDEN_SITE,
                 IDEMPOTENT_IN_PROGRESS, IDEMPOTENT_KEY_CONFLICT, RATE_LIMITED,
                 DEPENDENCY_UNAVAILABLE, INTERNAL_ERROR -> true;
            default -> false;
        };
    }
}
