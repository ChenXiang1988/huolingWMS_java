package com.huoling.wms.common.error;

/**
 * 货主档案契约错误码，逐行对应本次变更契约章 1.4「错误码表」，取值即契约里的符号码。
 *
 * <p>只新增两枚：{@code RULE_DUPLICATE_CREDIT_CODE} 与 {@code NOT_FOUND_OWNER}；
 * 其余引用 {@link ArchiveContractCodes} 的既有常量，不复制字面量——两处各写一份字符串必然漂移。
 * 为什么不把两枚新码塞进 {@link ArchiveContractCodes}：那样商品档案接口也能发出货主档案的码，
 * 违背「本表没有的码不允许在代码里出现」的封闭口径。
 *
 * <p>身份模块的 {@code AUTHZ_2001}、{@code AUTH_1002}、{@code AUTH_1003} 不在这里重复定义：
 * 它们归身份模块契约，本表只收本变更引入或复用的业务码。
 */
public final class OwnerContractCodes {

    /** 字段缺失、越界或枚举非法（400）。 */
    public static final String PARAM_INVALID = ArchiveContractCodes.PARAM_INVALID;
    /** 统一社会信用代码与既有档案重复，含并发后到者（422）。 */
    public static final String RULE_DUPLICATE_CREDIT_CODE = "RULE_DUPLICATE_CREDIT_CODE";
    /** 状态迁移不在守卫表内：停用态改档、重复停用、对可用再启用（409）。 */
    public static final String STATE_INVALID = ArchiveContractCodes.STATE_INVALID;
    /** 版本号落后（409，可重试）。 */
    public static final String CONFLICT_VERSION = ArchiveContractCodes.CONFLICT_VERSION;
    /** 货主不存在或不在调用方货主域内（404，两者同一响应）。 */
    public static final String NOT_FOUND_OWNER = "NOT_FOUND_OWNER";
    /** 端标识不是 HUB、货主域为空、非全域建档（403）。 */
    public static final String FORBIDDEN_OWNER = ArchiveContractCodes.FORBIDDEN_OWNER;
    /** 同一幂等键的首次受理仍在处理（409，可重试）。 */
    public static final String IDEMPOTENT_IN_PROGRESS = ArchiveContractCodes.IDEMPOTENT_IN_PROGRESS;
    /** 同一幂等键但请求指纹不同（409）。 */
    public static final String IDEMPOTENT_KEY_CONFLICT =
        ArchiveContractCodes.IDEMPOTENT_KEY_CONFLICT;
    /** 运营端该接口超过配额（429，可重试）。 */
    public static final String RATE_LIMITED = ArchiveContractCodes.RATE_LIMITED;
    /** 权限、数据域或受理存储不可用（503，可重试）。 */
    public static final String DEPENDENCY_UNAVAILABLE = ArchiveContractCodes.DEPENDENCY_UNAVAILABLE;
    /** 未捕获系统异常（500）。 */
    public static final String INTERNAL_ERROR = ArchiveContractCodes.INTERNAL_ERROR;

    private OwnerContractCodes() {
    }

    /** 是否属于货主档案契约错误码集合；用于拒绝契约外取值进入响应体。 */
    public static boolean known(String code) {
        if (code == null) {
            return false;
        }
        return switch (code) {
            case PARAM_INVALID, RULE_DUPLICATE_CREDIT_CODE, STATE_INVALID, CONFLICT_VERSION,
                 NOT_FOUND_OWNER, FORBIDDEN_OWNER, IDEMPOTENT_IN_PROGRESS,
                 IDEMPOTENT_KEY_CONFLICT, RATE_LIMITED, DEPENDENCY_UNAVAILABLE, INTERNAL_ERROR -> true;
            default -> false;
        };
    }
}
