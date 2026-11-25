package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.common.domain.AggregateRoot;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuArchiveFields;
import com.huoling.wms.masterdata.domain.SkuArchiveStateMachine;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import com.huoling.wms.masterdata.domain.SkuArchiveVersionRule;
import com.huoling.wms.masterdata.domain.SkuChangeRequestRule;
import com.huoling.wms.masterdata.domain.SkuChangeableField;
import com.huoling.wms.masterdata.domain.SkuRuleCodes;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;

import java.time.Instant;

/**
 * 商品档案聚合（Hub 为权威源）。
 *
 * <p>状态迁移一律经 Kernel 的 {@link SkuArchiveStateMachine}，判重经
 * {@link com.huoling.wms.masterdata.domain.SkuArchiveDedupRule}，版本递增经
 * {@link SkuArchiveVersionRule}；本类不复制这些判定。
 *
 * <p>归属货主与系统编号创建后不可变（INV-06、`26` 3.1）：本类没有对应的写方法，
 * 数据库侧还有触发器兜底，两层都不放行。
 */
@AggregateRoot
public class SkuArchive {

    private final Long id;
    private final Long ownerId;
    private final String archiveNo;
    private SkuArchiveFields fields;
    private SkuArchiveStatus status;
    private long version;
    private Instant activatedAt;
    private Instant dataTimestamp;
    private final Instant createdAt;
    private Instant updatedAt;

    private SkuArchive(Long id, Long ownerId, String archiveNo, SkuArchiveFields fields,
                       SkuArchiveStatus status, long version, Instant activatedAt,
                       Instant dataTimestamp, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.archiveNo = archiveNo;
        this.fields = fields;
        this.status = status;
        this.version = version;
        this.activatedAt = activatedAt;
        this.dataTimestamp = dataTimestamp;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 创建草稿：三种入口（货主端、运营代建、开放接口）带入归属后合流到这里，状态固定为草稿。
     * 草稿态不做判重（`26` 3.3）。
     */
    public static SkuArchive createDraft(Long id, Long ownerId, String archiveNo,
                                         SkuArchiveFields fields, Instant now) {
        if (ownerId == null) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "档案必须归属一个货主");
        }
        if (archiveNo == null || archiveNo.isBlank()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "缺少系统编号");
        }
        fields.requireComplete();
        return new SkuArchive(id, ownerId, archiveNo, fields, SkuArchiveStatus.DRAFT, 0L,
            null, now, now, now);
    }

    /** 从库中重建。 */
    public static SkuArchive restore(Long id, Long ownerId, String archiveNo, SkuArchiveFields fields,
                                     SkuArchiveStatus status, long version, Instant activatedAt,
                                     Instant dataTimestamp, Instant createdAt, Instant updatedAt) {
        return new SkuArchive(id, ownerId, archiveNo, fields, status, version, activatedAt,
            dataTimestamp, createdAt, updatedAt);
    }

    /** 提交审核：草稿、驳回、停用三态均可提交，停用态即「提交恢复」。 */
    public void submit(Long expectedVersion, Instant now) {
        if (expectedVersion != null) {
            SkuArchiveVersionRule.requireMatches(version, expectedVersion);
        }
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.SUBMIT);
        fields.requireComplete();
        bump(now);
    }

    /** 草稿或驳回态编辑：启用态字段变更必须走变更申请或审核流程，不得直接覆盖。 */
    public void edit(Long expectedVersion, SkuArchiveFields newFields, Instant now) {
        SkuArchiveVersionRule.requireMatches(version, expectedVersion);
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.EDIT);
        newFields.requireComplete();
        this.fields = newFields;
        bump(now);
    }

    /** 审核通过：进入启用，记录生效时间与版本递增（specs product-archive-review）。 */
    public void approve(Long expectedVersion, Instant now) {
        SkuArchiveVersionRule.requireMatches(version, expectedVersion);
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.APPROVE);
        this.activatedAt = now;
        bump(now);
    }

    /** 驳回：必须给出原因，原因由审核留痕保存。 */
    public void reject(String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "驳回必须给出原因");
        }
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.REJECT);
        bump(now);
    }

    /** 停用：只拦新接单据，数据保留、可追溯（INV-18）。 */
    public void disable(Long expectedVersion, String reason, Instant now) {
        SkuArchiveVersionRule.requireMatches(version, expectedVersion);
        if (reason == null || reason.isBlank()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "停用必须给出原因");
        }
        status = SkuArchiveStateMachine.transit(status, SkuArchiveAction.DISABLE);
        bump(now);
    }

    /** 变更申请审核通过后写回：只作用于启用态档案，版本递增后下行。 */
    public void applyChange(SkuChangeableField field, String targetValue, Instant now) {
        SkuChangeRequestRule.requireArchiveActive(status);
        this.fields = fields.withChange(field, targetValue);
        this.fields.requireComplete();
        bump(now);
    }

    private void bump(Instant now) {
        this.version = SkuArchiveVersionRule.next(version);
        this.dataTimestamp = now;
        this.updatedAt = now;
    }

    public Long id() {
        return id;
    }

    public Long ownerId() {
        return ownerId;
    }

    public String archiveNo() {
        return archiveNo;
    }

    public SkuArchiveFields fields() {
        return fields;
    }

    public SkuArchiveStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Instant activatedAt() {
        return activatedAt;
    }

    public Instant dataTimestamp() {
        return dataTimestamp;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** 停用标记与状态同步，唯一索引条件用同一个口径。 */
    public boolean disabled() {
        return status == SkuArchiveStatus.DISABLED;
    }
}
