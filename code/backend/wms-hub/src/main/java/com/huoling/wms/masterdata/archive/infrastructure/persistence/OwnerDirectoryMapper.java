package com.huoling.wms.masterdata.archive.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 货主选择器查询（API-020）。
 *
 * <p>只读投影：按归属货主分组取货主标识与展示名（列别名即契约响应字段 {@code ownerName}），
 * 键集分页锚在 {@code owner_id}。
 *
 * <p>筛选口径照抄契约 API-020 请求行：{@code ownerCode} 按货主标识精确匹配（等值，不做前后缀），
 * {@code name} 按展示名模糊匹配（{@code like}，作用在与响应同源的 {@code min(account_name)} 上，
 * 因此不会出现「筛选命中但展示名不含关键字」的行）；两个条件同时给出时在同一查询里取交集。
 *
 * <p>货主档案表落地前，货主集合从货主端账号派生（已登记为契约缺口），
 * 因此这里不写 {@code is_internal} 一类内部标记——所有货主同构（D01、D03）。
 */
@Mapper
public interface OwnerDirectoryMapper {

    @Select("""
        <script>
        select a.owner_id as ownerId, min(a.account_name) as ownerName
          from wms_account a
         where a.owner_id is not null
           and a.status = 'ENABLED'
        <if test="ownerCode != null and ownerCode != ''">
           and cast(a.owner_id as varchar) = #{ownerCode}
        </if>
        <if test="cursorId != null">
           and a.owner_id &gt; #{cursorId}
        </if>
         group by a.owner_id
        <if test="name != null and name != ''">
        having min(a.account_name) like '%' || #{name} || '%'
        </if>
         order by a.owner_id asc
         limit #{limit}
        </script>
        """)
    List<OwnerDirectoryPO> page(@Param("ownerCode") String ownerCode,
                                @Param("name") String name,
                                @Param("cursorId") Long cursorId,
                                @Param("limit") int limit);
}
