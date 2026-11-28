package com.huoling.wms.site.domain;

import com.huoling.wms.masterdata.domain.OwnerStatus;

import java.time.Instant;

/**
 * 本仓的货主状态副本条目（{@code site_owner_status}，tasks 1.14）。
 *
 * <p>只读副本，权威源在 Hub（D12）：本记录不承载业务行为，只回答「这个货主现在能不能接新单」。
 * 状态取值直接用 kernel 的 {@link OwnerStatus}，单仓端不另立一套两态枚举
 * （AR-11：单仓端只允许引用 kernel 的 {@code OwnerStatus} 与 {@code OwnerFields}）。
 *
 * @param ownerId       货主标识，主键
 * @param ownerNo       货主编号，全局唯一
 * @param status        可用 / 停用
 * @param sourceVersion 已应用的权威版本号，乱序保护的比较基准
 * @param eventId       下行事件标识，按它去重
 * @param dataTimestamp 权威数据的产生时间；闸门用它判断副本是否已过陈旧阈值
 * @param updatedAt     本仓写入时间（消费时间），只作留痕
 */
public record SiteOwnerStatus(
        Long ownerId,
        String ownerNo,
        OwnerStatus status,
        long sourceVersion,
        String eventId,
        Instant dataTimestamp,
        Instant updatedAt) {

    /** 是否可用：闸门正常路径的判定口径。 */
    public boolean active() {
        return status != null && status.active();
    }
}
