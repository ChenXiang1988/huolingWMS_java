package com.huoling.wms.masterdata.domain;

/**
 * 变更申请规则：同一档案同时只允许一条待审核申请；只有待审核申请可被审核；
 * 驳回必须给出原因（本次变更 design「领域模型与状态机」与 specs product-archive-review）。
 */
public final class SkuChangeRequestRule {

    private SkuChangeRequestRule() {
    }

    /**
     * 发起申请前的校验。
     *
     * @param hasPending 该档案是否已存在待审核申请
     * @throws SkuRuleViolation 已存在待审核申请时为 {@code STATE_INVALID}
     */
    public static void requireNoPending(boolean hasPending) {
        if (hasPending) {
            throw new SkuRuleViolation(SkuRuleCodes.STATE_INVALID,
                "该档案已有待审核的变更申请，需先完成审核");
        }
    }

    /**
     * 审核前的状态守卫。
     *
     * @param current 申请当前状态
     * @param approve 是否通过
     * @param reason  驳回原因
     * @throws SkuRuleViolation 非待审核为 {@code STATE_INVALID}；驳回无原因为 {@code PARAM_INVALID}
     */
    public static void requireReviewable(SkuChangeRequestStatus current, boolean approve, String reason) {
        if (current != SkuChangeRequestStatus.PENDING) {
            throw new SkuRuleViolation(SkuRuleCodes.STATE_INVALID,
                "申请状态 " + current + " 不可审核");
        }
        if (!approve && (reason == null || reason.isBlank())) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "驳回必须给出原因");
        }
    }

    /** 审核后的申请状态。 */
    public static SkuChangeRequestStatus target(boolean approve) {
        return approve ? SkuChangeRequestStatus.APPROVED : SkuChangeRequestStatus.REJECTED;
    }

    /**
     * 变更申请只作用于启用态档案（specs product-archive-review：单仓对本仓可见的生效档案发起）。
     *
     * @throws SkuRuleViolation 档案不是启用态时 {@code STATE_INVALID}
     */
    public static void requireArchiveActive(SkuArchiveStatus archiveStatus) {
        if (archiveStatus != SkuArchiveStatus.ACTIVE) {
            throw new SkuRuleViolation(SkuRuleCodes.STATE_INVALID,
                "档案状态 " + archiveStatus + " 不接受变更申请");
        }
    }
}
