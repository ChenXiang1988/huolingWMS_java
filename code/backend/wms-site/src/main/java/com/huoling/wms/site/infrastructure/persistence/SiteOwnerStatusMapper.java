package com.huoling.wms.site.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 货主状态副本的读写（单表，一次按主键查询加一次 upsert）。
 *
 * <p>列名显式起别名并直接投影成 Map：与 {@code site_sku_snapshot} 的写法保持一致，
 * 不为一张两列查询的副本表再引入一层 PO。
 *
 * <p>upsert 带 {@code where excluded.source_version >= site_owner_status.source_version}：
 * 版本落后的事件不覆盖已有状态（乱序保护），同版本重复投递只更新同一行、不产生第二行。
 * 「不小于」而不是「大于」：至少一次投递下同一条事件会重复到达，重放同版本必须保持幂等。
 */
@Mapper
public interface SiteOwnerStatusMapper {

    @Select("""
        select owner_id      as "ownerId",
               owner_no      as "ownerNo",
               status,
               source_version as "sourceVersion",
               event_id      as "eventId",
               data_timestamp as "dataTimestamp",
               updated_at    as "updatedAt"
          from site_owner_status
         where owner_id = #{ownerId}
        """)
    Map<String, Object> find(@Param("ownerId") long ownerId);

    @Insert("""
        insert into site_owner_status (owner_id, owner_no, status, source_version, event_id,
            data_timestamp, updated_at)
        values (#{s.ownerId}, #{s.ownerNo}, #{s.status}, #{s.sourceVersion}, #{s.eventId},
            #{s.dataTimestamp}, #{s.updatedAt})
        on conflict (owner_id) do update
           set owner_no = excluded.owner_no,
               status = excluded.status,
               source_version = excluded.source_version,
               event_id = excluded.event_id,
               data_timestamp = excluded.data_timestamp,
               updated_at = excluded.updated_at
         where excluded.source_version >= site_owner_status.source_version
        """)
    int upsert(@Param("s") Map<String, Object> status);
}
