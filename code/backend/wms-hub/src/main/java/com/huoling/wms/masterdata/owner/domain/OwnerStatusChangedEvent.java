package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.masterdata.domain.OwnerAction;
import com.huoling.wms.masterdata.domain.OwnerStatus;

import java.time.Instant;

/**
 * 货主状态变更事件（契约 1.9、design「停用货主如何阻断单仓端新建业务」）。
 *
 * <p>载荷字段与契约逐字一致：{@code {eventId, ownerId, ownerNo, status, action, changedAt, version}}。
 * <b>不带手机号与统一社会信用代码</b>：单仓端只需要「这个货主现在能不能接新单」，
 * 联系人与证照不是它判断所需的字段，放进下行载荷等于把主数据敏感项复制到每个仓。
 *
 * <p>{@code eventId = ownerNo + "#" + version}，与既有 {@code ArchiveOutboxMessage.eventIdOf} 同一形态，
 * 消费方按它幂等；版本号递增保证停用与启用各产生一个新事件标识。
 *
 * <p>只在<strong>建档、停用、启用</strong>三条路径发出：改档不改状态，因而不发（design「触发点」）。
 */
public record OwnerStatusChangedEvent(
        String eventId,
        String ownerId,
        String ownerNo,
        OwnerStatus status,
        OwnerAction action,
        Instant changedAt,
        long version) {

    /** {@code hub_outbox.event_type} 的取值；投递器按 {@code OWNER} 前缀解析路由键。 */
    public static final String TYPE = "owner.status.changed";

    public static String eventIdOf(String ownerNo, long version) {
        return ownerNo + "#" + version;
    }

    /**
     * 从聚合取事件：{@code ownerId} 用十进制字符串（契约 1.5：标识一律字符串，避免调用方丢精度）。
     *
     * <p>动作与时间取聚合上最近一次状态变更的留痕，与详情接口的 {@code lastStatusChange} 同源，
     * 不会出现「详情说停用于十点、下行说十一点」。
     */
    public static OwnerStatusChangedEvent of(OwnerArchive archive) {
        OwnerStatusChange change = archive.lastStatusChange();
        return new OwnerStatusChangedEvent(
            eventIdOf(archive.ownerNo(), archive.version()),
            String.valueOf(archive.id()),
            archive.ownerNo(),
            archive.status(),
            change.action(),
            change.changedAt(),
            archive.version());
    }
}
