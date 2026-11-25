package com.huoling.wms.masterdata.domain;

/**
 * 版本号规则：每次成功变更递增一，端间一致性以版本号为基准（本次变更 design「强制手段分层」）。
 *
 * <p>乐观锁的前置校验在这里，最终判定仍由数据库 {@code update ... where version = ?} 的受影响行数兜底；
 * 两者都用同一个期望值，冲突一律 {@code CONFLICT_VERSION}（可重试）。
 */
public final class SkuArchiveVersionRule {

    private SkuArchiveVersionRule() {
    }

    /** 下一次变更后的版本号。 */
    public static long next(long current) {
        if (current < 0) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "版本号不得为负数");
        }
        return current + 1;
    }

    /**
     * 客户端提交的版本必须是当前版本。
     *
     * @param current  库中当前版本
     * @param expected 请求携带的版本；未携带按参数错误处理
     * @throws SkuRuleViolation 未携带为 {@code PARAM_INVALID}，落后为 {@code CONFLICT_VERSION}
     */
    public static void requireMatches(long current, Long expected) {
        if (expected == null) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "字段 version 必填");
        }
        if (current != expected) {
            throw new SkuRuleViolation(SkuRuleCodes.CONFLICT_VERSION,
                "档案已被修改，当前版本 " + current + "，请刷新后重提");
        }
    }
}
