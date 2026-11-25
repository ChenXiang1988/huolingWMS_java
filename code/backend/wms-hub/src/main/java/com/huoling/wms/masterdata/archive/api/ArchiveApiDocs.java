package com.huoling.wms.masterdata.archive.api;

/**
 * 写接口的 SpringDoc 响应描述（本次变更 tasks 1.16；契约「写接口统一响应」与「错误码表」）。
 *
 * <p>为什么集中在一处：契约错误码是按 HTTP 状态分组的（409 一档里就有状态冲突、版本冲突与两种幂等错误），
 * 每个接口各写一遍描述必然写歪，重导的 OpenAPI 就会与契约章不一致。这里按状态给出描述，
 * 描述里列出该状态下契约允许的全部错误码，接口只挑自己条目里出现的那几档。
 *
 * <p>取值是编译期常量，可直接作为 {@code @ApiResponse} 的属性值。
 */
final class ArchiveApiDocs {

    private ArchiveApiDocs() {
    }

    /** 202：写接口统一受理响应，只含受理号与受理时状态快照。 */
    static final String ACCEPTED =
        "已受理：data 只含 acceptNo 与受理时状态快照 status，执行结果由 API-015 至 API-017 按受理号查询";
    /** 400：入口同步校验失败，不进入异步受理。 */
    static final String PARAM_INVALID = "PARAM_INVALID：字段缺失、长度越界或枚举非法";
    /** 403：数据域越权（货主越权访问他人档案）。 */
    static final String FORBIDDEN_OWNER = "FORBIDDEN_OWNER：货主越权访问他人档案，或运营代建未指定归属货主";
    /** 403：数据域越权（单仓跨仓发起变更申请）。 */
    static final String FORBIDDEN_SITE = "FORBIDDEN_SITE：单仓越权发起变更申请";
    /** 404：目标不存在或不在数据域内。 */
    static final String NOT_FOUND_ARCHIVE = "NOT_FOUND_ARCHIVE：档案不存在或不在数据域内";
    /** 409：状态迁移、版本冲突与两种幂等错误同一档。 */
    static final String CONFLICT =
        "STATE_INVALID、CONFLICT_VERSION、IDEMPOTENT_IN_PROGRESS、IDEMPOTENT_KEY_CONFLICT："
            + "状态迁移不在守卫表内、版本号落后、幂等记录处理中或同键异体";
    /** 422：货主内编码或条码重复（提交判重）。 */
    static final String DUPLICATE =
        "RULE_DUPLICATE_CODE、RULE_DUPLICATE_BARCODE：货主内编码或条码重复";
    /** 429：限流，带重试间隔。 */
    static final String RATE_LIMITED = "RATE_LIMITED：端加货主维度超限，响应体带 retryAfterMs";
    /** 503：依赖不可用，可重试。 */
    static final String DEPENDENCY_UNAVAILABLE =
        "DEPENDENCY_UNAVAILABLE：下行或缓存依赖不可用，或受理线程池已满，可重试";
    /** 500：未捕获系统异常。 */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR：未捕获系统异常，带 traceId 报障";

    /** 202 的状态码。 */
    static final String CODE_ACCEPTED = "202";
    /** 400 的状态码。 */
    static final String CODE_BAD_REQUEST = "400";
    /** 403 的状态码。 */
    static final String CODE_FORBIDDEN = "403";
    /** 404 的状态码。 */
    static final String CODE_NOT_FOUND = "404";
    /** 409 的状态码。 */
    static final String CODE_CONFLICT = "409";
    /** 422 的状态码。 */
    static final String CODE_UNPROCESSABLE = "422";
    /** 429 的状态码。 */
    static final String CODE_RATE_LIMITED = "429";
    /** 500 的状态码。 */
    static final String CODE_INTERNAL = "500";
    /** 503 的状态码。 */
    static final String CODE_UNAVAILABLE = "503";
}
