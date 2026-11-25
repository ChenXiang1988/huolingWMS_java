package com.huoling.wms.masterdata.archive.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxRepository;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OutboxMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OutboxPO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 本地消息表仓储实现：初始状态为待投递，失败由定时补偿按重试次数字段退避（22 T-05）。 */
@Repository
public class ArchiveOutboxRepositoryImpl implements ArchiveOutboxRepository {

    private static final String STATUS_PENDING = "PENDING";

    private final OutboxMapper mapper;

    public ArchiveOutboxRepositoryImpl(OutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ArchiveOutboxMessage message) {
        OutboxPO po = new OutboxPO();
        po.setId(message.id() == null ? IdWorker.getId() : message.id());
        po.setEventId(message.eventId());
        po.setEventType(message.eventType());
        po.setAggregateNo(message.aggregateNo());
        po.setVersion(message.version());
        po.setOwnerId(message.ownerId());
        po.setPayloadJson(message.payloadJson());
        po.setStatus(STATUS_PENDING);
        po.setRetryCount(0);
        po.setCreatedAt(offset(message.createdAt()));
        mapper.insert(po);
    }

    @Override
    public List<ArchiveOutboxMessage> findDispatchable(int limit, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        return mapper.findDispatchable(limit, offset(now)).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean claim(Long id) {
        return id != null && mapper.claim(id, offset(Instant.now())) == 1;
    }

    @Override
    public void markSent(Long id, Instant now) {
        OutboxPO po = new OutboxPO();
        po.setId(id);
        po.setStatus("SENT");
        po.setDispatchedAt(offset(now));
        mapper.updateById(po);
    }

    @Override
    public void markFailed(Long id, String error, Instant now) {
        String trimmed = error == null ? null
            : (error.length() > 256 ? error.substring(0, 256) : error);
        mapper.markFailed(id, trimmed, offset(now));
    }

    private ArchiveOutboxMessage toDomain(OutboxPO po) {
        return new ArchiveOutboxMessage(po.getId(), po.getEventId(), po.getEventType(),
            po.getAggregateNo(), po.getVersion() == null ? 0L : po.getVersion(), po.getOwnerId(),
            po.getPayloadJson(), instant(po.getCreatedAt()));
    }

    private OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
