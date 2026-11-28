package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.common.domain.ValueObject;
import com.huoling.wms.masterdata.domain.OwnerAction;

import java.time.Instant;

/**
 * 最近一次状态变更（契约 1.5 的 {@code lastStatusChange}）：动作、原因、操作人与时间。
 *
 * <p>落在聚合同一行，<b>不另建聚合、不另建留痕表</b>：建留痕表就要跨聚合同事务，
 * 而货主档案只需要「最近一次」——历史变更由 {@code hub_outbox} 的下行事件与审计日志承载。
 *
 * @param action     变更动作：建档、停用、启用（改档不改状态，不产生新值）
 * @param reason     原因：建档时为空，停用与启用必填
 * @param operatorId 操作账号
 * @param changedAt  变更时间（UTC）
 */
@ValueObject
public record OwnerStatusChange(OwnerAction action, String reason, long operatorId, Instant changedAt) {
}
