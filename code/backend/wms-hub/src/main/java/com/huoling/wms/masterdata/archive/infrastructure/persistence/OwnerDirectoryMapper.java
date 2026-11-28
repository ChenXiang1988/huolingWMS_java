package com.huoling.wms.masterdata.archive.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 货主选择器查询（API-020），数据源是权威主数据 {@code wms_owner}（tasks 1.12）。
 *
 * <p>四条口径落在 SQL 上，业务层不再各判一遍：
 * <ul>
 *   <li>只返回可用货主：条件固定 {@code status = 'ACTIVE'}，停用档不出现；</li>
 *   <li>不再从账号派生：不查 {@code wms_account}，断掉账号数据仍能查到货主；</li>
 *   <li>检索：{@code owner_no} 精确、{@code owner_name} 模糊（{@code %keyword%} 走 pg_trgm 索引）；</li>
 *   <li>数据域：域为「全部」不加条件，域为「指定列表」加 {@code id in (...)}，空列表一条都不返回，
 *       绝不回落成全量（INV-06、OI-08）。</li>
 * </ul>
 *
 * <p>键集分页用行值比较 {@code (排序键, owner_no) > (?, ?)}（降序用 {@code <}），
 * 与 {@code order by 排序键, owner_no} 完全同序：同值行不重复也不漏，
 * 且能用上 {@code idx_wms_owner_sort_name} 与 {@code uk_wms_owner_no}。
 */
@Mapper
public interface OwnerDirectoryMapper {

    @Select("""
        <script>
        select o.id         as ownerId,
               o.owner_no   as ownerNo,
               o.owner_name as ownerName
          from wms_owner o
         where o.status = 'ACTIVE'
        <if test="ownerNo != null and ownerNo != ''">
           and o.owner_no = #{ownerNo}
        </if>
        <if test="ownerName != null and ownerName != ''">
           and o.owner_name like '%' || #{ownerName} || '%'
        </if>
        <if test="!allOwners">
           and o.id in
           <foreach collection="ownerIds" item="ownerId" open="(" separator="," close=")">#{ownerId}</foreach>
        </if>
        <if test="cursorOwnerNo != null">
           and
           <choose>
             <when test="sortKey == 'OWNER_NAME'">
               <choose>
                 <when test="ascending">(o.owner_name, o.owner_no) &gt; (#{cursorValue}, #{cursorOwnerNo})</when>
                 <otherwise>(o.owner_name, o.owner_no) &lt; (#{cursorValue}, #{cursorOwnerNo})</otherwise>
               </choose>
             </when>
             <otherwise>
               <choose>
                 <when test="ascending">o.owner_no &gt; #{cursorValue}</when>
                 <otherwise>o.owner_no &lt; #{cursorValue}</otherwise>
               </choose>
             </otherwise>
           </choose>
        </if>
         order by
         <choose>
           <when test="sortKey == 'OWNER_NAME'">o.owner_name</when>
           <otherwise>o.owner_no</otherwise>
         </choose>
         <choose>
           <when test="ascending">asc,</when>
           <otherwise>desc,</otherwise>
         </choose>
         o.owner_no
         <choose>
           <when test="ascending">asc</when>
           <otherwise>desc</otherwise>
         </choose>
         limit #{limit}
        </script>
        """)
    List<OwnerDirectoryPO> page(@Param("ownerNo") String ownerNo,
                                @Param("ownerName") String ownerName,
                                @Param("allOwners") boolean allOwners,
                                @Param("ownerIds") Collection<Long> ownerIds,
                                @Param("sortKey") String sortKey,
                                @Param("ascending") boolean ascending,
                                @Param("cursorValue") String cursorValue,
                                @Param("cursorOwnerNo") String cursorOwnerNo,
                                @Param("limit") int limit);
}
