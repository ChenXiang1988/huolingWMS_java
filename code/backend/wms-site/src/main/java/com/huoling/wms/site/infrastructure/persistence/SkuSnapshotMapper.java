package com.huoling.wms.site.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 本仓生效档案缓存读写。
 *
 * <p>列名显式起别名，直接投影成 Map 再转领域对象：单表只读加一次 upsert，
 * 不值得再引入一层 PO；别名一律用双引号保持驼峰，避免 PostgreSQL 把标识符折成小写。
 *
 * <p>upsert 带 {@code where excluded.version > site_sku_snapshot.version}：
 * 版本落后或相等的摘要不覆盖已有缓存（契约「一致性：版本落后的缓存被标记」）。
 */
@Mapper
public interface SkuSnapshotMapper {

    @Select("""
        select id, warehouse_id as "siteId", owner_id as "ownerId", archive_id as "archiveId",
               archive_no as "archiveNo", owner_code as "ownerCode", name, barcode,
               base_unit as "baseUnit", storage_condition as "storageCondition", category,
               specification, status, version, event_id as "eventId",
               data_timestamp as "dataTimestamp", applied_at as "appliedAt"
          from site_sku_snapshot
         where warehouse_id = #{siteId}
           and archive_no = #{archiveNo}
        """)
    Map<String, Object> find(@Param("siteId") Long siteId, @Param("archiveNo") String archiveNo);

    /**
     * 本仓列表：排序与游标比较都按契约 {@code sortBy} 落列（{@code updateTime} 落数据时间戳，
     * {@code ownerCode} 落货主编码，{@code name} 落名称），主键恒为次级键。
     *
     * <p>列名由 {@code sortKey} 的闭集分支给出，不接受外部串拼接，因此没有注入面；
     * 排序键与游标比较使用同一列同一方向，同值行靠主键继续前进，翻页不重不漏。
     */
    @Select("""
        <script>
        select id, warehouse_id as "siteId", owner_id as "ownerId", archive_id as "archiveId",
               archive_no as "archiveNo", owner_code as "ownerCode", name, barcode,
               base_unit as "baseUnit", storage_condition as "storageCondition", category,
               specification, status, version, event_id as "eventId",
               data_timestamp as "dataTimestamp", applied_at as "appliedAt"
          from site_sku_snapshot
         where warehouse_id = #{siteId}
        <if test="ownerCode != null and ownerCode != ''">
           and owner_code = #{ownerCode}
        </if>
        <if test="name != null and name != ''">
           and name like '%' || #{name} || '%'
        </if>
        <if test="category != null and category != ''">
           and category = #{category}
        </if>
        <if test="updatedFrom != null">
           and data_timestamp &gt;= #{updatedFrom}
        </if>
        <if test="updatedTo != null">
           and data_timestamp &lt;= #{updatedTo}
        </if>
        <if test="cursorId != null">
          <choose>
            <when test="sortKey == 'OWNER_CODE'">
              <choose>
                <when test="sortAsc">
           and (owner_code &gt; #{cursorText} or (owner_code = #{cursorText} and id &gt; #{cursorId}))
                </when>
                <otherwise>
           and (owner_code &lt; #{cursorText} or (owner_code = #{cursorText} and id &lt; #{cursorId}))
                </otherwise>
              </choose>
            </when>
            <when test="sortKey == 'NAME'">
              <choose>
                <when test="sortAsc">
           and (name &gt; #{cursorText} or (name = #{cursorText} and id &gt; #{cursorId}))
                </when>
                <otherwise>
           and (name &lt; #{cursorText} or (name = #{cursorText} and id &lt; #{cursorId}))
                </otherwise>
              </choose>
            </when>
            <otherwise>
              <choose>
                <when test="sortAsc">
           and (data_timestamp &gt; #{cursorTime}
                or (data_timestamp = #{cursorTime} and id &gt; #{cursorId}))
                </when>
                <otherwise>
           and (data_timestamp &lt; #{cursorTime}
                or (data_timestamp = #{cursorTime} and id &lt; #{cursorId}))
                </otherwise>
              </choose>
            </otherwise>
          </choose>
        </if>
         order by
        <choose>
          <when test="sortKey == 'OWNER_CODE'">owner_code</when>
          <when test="sortKey == 'NAME'">name</when>
          <otherwise>data_timestamp</otherwise>
        </choose>
        <choose>
          <when test="sortAsc">asc, id asc</when>
          <otherwise>desc, id desc</otherwise>
        </choose>
         limit #{limit}
        </script>
        """)
    List<Map<String, Object>> page(@Param("siteId") Long siteId,
                                   @Param("ownerCode") String ownerCode,
                                   @Param("name") String name,
                                   @Param("category") String category,
                                   @Param("updatedFrom") java.time.OffsetDateTime updatedFrom,
                                   @Param("updatedTo") java.time.OffsetDateTime updatedTo,
                                   @Param("sortKey") String sortKey,
                                   @Param("sortAsc") boolean sortAsc,
                                   @Param("cursorId") Long cursorId,
                                   @Param("cursorTime") java.time.OffsetDateTime cursorTime,
                                   @Param("cursorText") String cursorText,
                                   @Param("limit") int limit);

    @Insert("""
        insert into site_sku_snapshot (id, warehouse_id, owner_id, archive_id, archive_no, owner_code,
            name, barcode, base_unit, storage_condition, category, specification, status, version,
            event_id, data_timestamp, applied_at)
        values (#{s.id}, #{s.siteId}, #{s.ownerId}, #{s.archiveId}, #{s.archiveNo}, #{s.ownerCode},
            #{s.name}, #{s.barcode}, #{s.baseUnit}, #{s.storageCondition}, #{s.category},
            #{s.specification}, #{s.status}, #{s.version}, #{s.eventId}, #{s.dataTimestamp},
            #{s.appliedAt})
        on conflict (warehouse_id, archive_no) do update
           set owner_id = excluded.owner_id,
               archive_id = excluded.archive_id,
               owner_code = excluded.owner_code,
               name = excluded.name,
               barcode = excluded.barcode,
               base_unit = excluded.base_unit,
               storage_condition = excluded.storage_condition,
               category = excluded.category,
               specification = excluded.specification,
               status = excluded.status,
               version = excluded.version,
               event_id = excluded.event_id,
               data_timestamp = excluded.data_timestamp,
               applied_at = excluded.applied_at
         where excluded.version > site_sku_snapshot.version
        """)
    int upsert(@Param("s") Map<String, Object> snapshot);
}
