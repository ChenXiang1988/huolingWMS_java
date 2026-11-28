package com.huoling.wms.masterdata.owner.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/**
 * 货主状态变更消息的写入（{@code hub_outbox}，与商品档案共用同一张本地消息表）。
 *
 * <p>只插入、不改状态：投递与重试全部由 {@code ArchiveOutboxDispatcher} 负责，
 * 业主侧不参与投递，避免「停用货主」的成败取决于消息中间件是否可用。
 *
 * <p>初始状态 {@code PENDING}、重试次数 0，与既有投递器的取值闭集一致
 * （{@code ck_hub_outbox_status check (status in ('PENDING','SENT','FAILED'))}）。
 */
@Mapper
public interface OwnerStatusOutboxMapper {

    @Insert("""
        insert into hub_outbox (id, event_id, event_type, aggregate_no, version, owner_id,
            payload_json, status, retry_count, created_at)
        values (#{id}, #{eventId}, #{eventType}, #{aggregateNo}, #{version}, #{ownerId},
            #{payloadJson}, 'PENDING', 0, #{createdAt})
        """)
    int insertOutbox(@Param("id") Long id,
                     @Param("eventId") String eventId,
                     @Param("eventType") String eventType,
                     @Param("aggregateNo") String aggregateNo,
                     @Param("version") long version,
                     @Param("ownerId") long ownerId,
                     @Param("payloadJson") String payloadJson,
                     @Param("createdAt") OffsetDateTime createdAt);
}
