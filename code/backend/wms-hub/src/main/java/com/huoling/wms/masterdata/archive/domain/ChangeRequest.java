package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.common.domain.AggregateRoot;
import com.huoling.wms.masterdata.domain.SkuChangeRequestRule;
import com.huoling.wms.masterdata.domain.SkuChangeRequestStatus;
import com.huoling.wms.masterdata.domain.SkuChangeableField;
import com.huoling.wms.masterdata.domain.SkuRuleCodes;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;

import java.time.Instant;

/**
 * 单仓主数据字段变更申请聚合。
 *
 * <p>单仓端只能发起申请，不能改写档案（D12、specs product-archive-review）；
 * 审核通过后由 Hub 写回权威档案并递增版本号。同一档案同时只允许一条待审核申请，
 * 判定在 {@link SkuChangeRequestRule}，数据库侧还有部分唯一索引兜底。
 */
@AggregateRoot
public class ChangeRequest {

    private final Long id;
    private final String requestNo;
    private final Long archiveId;
    private final String archiveNo;
    private final Long ownerId;
    private final Long warehouseId;
    private final SkuChangeableField field;
    private final String targetValue;
    private final String reason;
    private SkuChangeRequestStatus status;
    private String reviewReason;
    private Long archiveVersion;
    private long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private ChangeRequest(Long id, String requestNo, Long archiveId, String archiveNo, Long ownerId,
                          Long warehouseId, SkuChangeableField field, String targetValue, String reason,
                          SkuChangeRequestStatus status, String reviewReason, Long archiveVersion,
                          long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.requestNo = requestNo;
        this.archiveId = archiveId;
        this.archiveNo = archiveNo;
        this.ownerId = ownerId;
        this.warehouseId = warehouseId;
        this.field = field;
        this.targetValue = targetValue;
        this.reason = reason;
        this.status = status;
        this.reviewReason = reviewReason;
        this.archiveVersion = archiveVersion;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 发起申请：状态固定为待审核，原档案内容不变。 */
    public static ChangeRequest create(Long id, String requestNo, Long archiveId, String archiveNo,
                                       Long ownerId, Long warehouseId, SkuChangeableField field,
                                       String targetValue, String reason, Instant now) {
        if (ownerId == null || warehouseId == null) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "变更申请必须带归属货主与发起仓");
        }
        if (field == null) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "缺少变更字段");
        }
        if (targetValue == null || targetValue.isBlank()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "缺少变更目标值");
        }
        if (reason == null || reason.isBlank()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "变更申请必须给出原因");
        }
        return new ChangeRequest(id, requestNo, archiveId, archiveNo, ownerId, warehouseId, field,
            targetValue, reason, SkuChangeRequestStatus.PENDING, null, null, 0L, now, now);
    }

    public static ChangeRequest restore(Long id, String requestNo, Long archiveId, String archiveNo,
                                        Long ownerId, Long warehouseId, SkuChangeableField field,
                                        String targetValue, String reason, SkuChangeRequestStatus status,
                                        String reviewReason, Long archiveVersion, long version,
                                        Instant createdAt, Instant updatedAt) {
        return new ChangeRequest(id, requestNo, archiveId, archiveNo, ownerId, warehouseId, field,
            targetValue, reason, status, reviewReason, archiveVersion, version, createdAt, updatedAt);
    }

    /**
     * 审核申请。
     *
     * @param approve        通过为 {@code true}，驳回为 {@code false}
     * @param reason         驳回原因，通过时可为空
     * @param archiveVersion 通过后写回的档案新版本号；驳回时传 {@code null}
     */
    public void review(boolean approve, String reason, Long archiveVersion, Instant now) {
        SkuChangeRequestRule.requireReviewable(status, approve, reason);
        this.status = SkuChangeRequestRule.target(approve);
        this.reviewReason = reason;
        this.archiveVersion = approve ? archiveVersion : null;
        this.version = this.version + 1;
        this.updatedAt = now;
    }

    public Long id() {
        return id;
    }

    public String requestNo() {
        return requestNo;
    }

    public Long archiveId() {
        return archiveId;
    }

    public String archiveNo() {
        return archiveNo;
    }

    public Long ownerId() {
        return ownerId;
    }

    public Long warehouseId() {
        return warehouseId;
    }

    public SkuChangeableField field() {
        return field;
    }

    public String targetValue() {
        return targetValue;
    }

    public String reason() {
        return reason;
    }

    public SkuChangeRequestStatus status() {
        return status;
    }

    public String reviewReason() {
        return reviewReason;
    }

    public Long archiveVersion() {
        return archiveVersion;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
