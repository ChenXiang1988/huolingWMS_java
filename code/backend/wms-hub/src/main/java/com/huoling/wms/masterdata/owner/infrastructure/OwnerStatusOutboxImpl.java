package com.huoling.wms.masterdata.owner.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusChangedEvent;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusOutbox;
import com.huoling.wms.masterdata.owner.infrastructure.persistence.OwnerStatusOutboxMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 货主状态变更消息写入实现：载荷序列化 + 一行 {@code hub_outbox}。
 *
 * <p>与业务状态同事务落库（T-07）：本方法在 {@code OwnerCommandService} 的事务内被调用，
 * 写失败即整笔回滚——「货主已停用但单仓端永不感知」不可接受，宁可让停用动作失败重试。
 *
 * <p>序列化失败按 {@code INTERNAL_ERROR} 处理（与商品档案下行摘要同一口径）：
 * 载荷是固定字段的记录，正常不会失败，一旦失败说明代码或类路径出了问题，不能静默吞掉。
 */
@Repository
public class OwnerStatusOutboxImpl implements OwnerStatusOutbox {

    private final OwnerStatusOutboxMapper mapper;
    private final ObjectMapper objectMapper;

    public OwnerStatusOutboxImpl(OwnerStatusOutboxMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(OwnerStatusChangedEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new OwnerContractException(OwnerErrorCodes.INTERNAL_ERROR, "货主状态事件序列化失败");
        }
        mapper.insertOutbox(IdWorker.getId(), event.eventId(), OwnerStatusChangedEvent.TYPE,
            event.ownerNo(), event.version(), Long.parseLong(event.ownerId()), json,
            OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }
}
