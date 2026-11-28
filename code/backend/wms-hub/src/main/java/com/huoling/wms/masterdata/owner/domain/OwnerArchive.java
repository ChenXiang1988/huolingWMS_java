package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.common.domain.AggregateRoot;
import com.huoling.wms.masterdata.domain.OwnerAction;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerNumberRule;
import com.huoling.wms.masterdata.domain.OwnerStateMachine;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.domain.OwnerVersionRule;

import java.time.Instant;

/**
 * 货主档案聚合（Hub 为权威源，M01 主数据）。
 *
 * <p>行为只有四个：建档、整档替换（改档）、停用、启用；状态与版本只在聚合内推进。
 * 状态迁移一律经 Kernel 的 {@link OwnerStateMachine}，字段校验经 {@link OwnerFields}，
 * 版本递增经 {@link OwnerVersionRule}；本类不复制这些判定（不变量只在 kernel 定义）。
 *
 * <p>编号与主键创建后不可变（OI-01）：本类没有对应的写方法，数据库侧触发器再兜一层。
 * 停用不是终态（可再启用），但没有任何系统路径能产生 {@code DISABLED → ACTIVE}——
 * 只有 {@link #enable} 一个入口，且必须带原因与操作人（OI-12）。
 */
@AggregateRoot
public class OwnerArchive {

    private final Long id;
    private final String ownerNo;
    private OwnerFields fields;
    private OwnerStatus status;
    private OwnerStatusChange lastStatusChange;
    private long version;
    private final Instant createdAt;
    private final long createdBy;
    private Instant updatedAt;
    private long updatedBy;

    private OwnerArchive(Long id, String ownerNo, OwnerFields fields, OwnerStatus status,
                         OwnerStatusChange lastStatusChange, long version, Instant createdAt,
                         long createdBy, Instant updatedAt, long updatedBy) {
        this.id = id;
        this.ownerNo = ownerNo;
        this.fields = fields;
        this.status = status;
        this.lastStatusChange = lastStatusChange;
        this.version = version;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /**
     * 建档：状态进入可用，版本为 0，状态留痕为建档本身（原因为空）。
     *
     * @throws com.huoling.wms.masterdata.domain.OwnerRuleViolation 编号形态非法、四项缺失时为
     *     {@code PARAM_INVALID}
     */
    public static OwnerArchive create(Long id, String ownerNo, OwnerFields fields, long operatorId,
                                      Instant now) {
        OwnerNumberRule.requireValid(ownerNo);
        if (fields == null) {
            throw new OwnerContractException(OwnerErrorCodes.PARAM_INVALID, "缺少货主档案字段");
        }
        fields.requireComplete();
        OwnerStatus created = OwnerStateMachine.transit(null, OwnerAction.CREATE);
        return new OwnerArchive(id, ownerNo, fields, created,
            new OwnerStatusChange(OwnerAction.CREATE, null, operatorId, now), 0L,
            now, operatorId, now, operatorId);
    }

    /** 从库中重建。 */
    public static OwnerArchive restore(Long id, String ownerNo, OwnerFields fields, OwnerStatus status,
                                       OwnerStatusChange lastStatusChange, long version,
                                       Instant createdAt, long createdBy, Instant updatedAt,
                                       long updatedBy) {
        return new OwnerArchive(id, ownerNo, fields, status, lastStatusChange, version, createdAt,
            createdBy, updatedAt, updatedBy);
    }

    /**
     * 改档：整档替换四项，状态不变。
     *
     * <p>停用态必须拒绝（须先启用）：守卫在 {@link OwnerStateMachine}；
     * 改档不写状态留痕、不写下行事件（合同口径：改档不改状态）。
     */
    public void edit(Long expectedVersion, OwnerFields newFields, Instant now, long operatorId) {
        OwnerVersionRule.requireMatches(version, expectedVersion);
        status = OwnerStateMachine.transit(status, OwnerAction.EDIT);
        if (newFields == null) {
            throw new OwnerContractException(OwnerErrorCodes.PARAM_INVALID, "缺少货主档案字段");
        }
        newFields.requireComplete();
        this.fields = newFields;
        bump(now, operatorId);
    }

    /** 停用：必须给出原因，状态与留痕同一次写入。 */
    public void disable(Long expectedVersion, String reason, Instant now, long operatorId) {
        OwnerVersionRule.requireMatches(version, expectedVersion);
        OwnerFields.requireReason(reason);
        status = OwnerStateMachine.transit(status, OwnerAction.DISABLE);
        lastStatusChange = new OwnerStatusChange(OwnerAction.DISABLE, reason, operatorId, now);
        bump(now, operatorId);
    }

    /** 重新启用：只能由人发起，必须给出原因，编号不变。 */
    public void enable(Long expectedVersion, String reason, Instant now, long operatorId) {
        OwnerVersionRule.requireMatches(version, expectedVersion);
        OwnerFields.requireReason(reason);
        status = OwnerStateMachine.transit(status, OwnerAction.ENABLE);
        lastStatusChange = new OwnerStatusChange(OwnerAction.ENABLE, reason, operatorId, now);
        bump(now, operatorId);
    }

    private void bump(Instant now, long operatorId) {
        this.version = OwnerVersionRule.next(version);
        this.updatedAt = now;
        this.updatedBy = operatorId;
    }

    public Long id() {
        return id;
    }

    public String ownerNo() {
        return ownerNo;
    }

    public OwnerFields fields() {
        return fields;
    }

    public OwnerStatus status() {
        return status;
    }

    /** 是否可用；选择器与受理闸门以它为准。 */
    public boolean active() {
        return status == OwnerStatus.ACTIVE;
    }

    public OwnerStatusChange lastStatusChange() {
        return lastStatusChange;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long createdBy() {
        return createdBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long updatedBy() {
        return updatedBy;
    }
}
