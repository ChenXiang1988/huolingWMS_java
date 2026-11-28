package com.huoling.wms.masterdata.identity.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 货主绑定信息的只读查询（{@code wms_owner}，tasks 1.15）。
 *
 * <p>两条查询对应两处用途，不提供第三条：
 * <ul>
 *   <li>单条：开通账号校验与登录/令牌校验的「是否停用」；</li>
 *   <li>批量：账号列表与详情回显 {@code ownerNo} / {@code ownerName}（契约 1.7 的四个响应字段）。</li>
 * </ul>
 *
 * <p>不带数据域条件：调用方是身份模块的账号用例（权限管理员开通账号、账号本人登录），
 * 不是货主档案查询接口。货主档案接口的域收窄在 {@code masterdata.owner} 内完成（INV-19）。
 */
@Mapper
public interface OwnerBindingMapper {

    @Select("""
        select o.id         as ownerId,
               o.owner_no   as ownerNo,
               o.owner_name as ownerName,
               o.status     as status
          from wms_owner o
         where o.id = #{ownerId}
        """)
    OwnerBindingPO find(@Param("ownerId") long ownerId);

    @Select("""
        <script>
        select o.id         as ownerId,
               o.owner_no   as ownerNo,
               o.owner_name as ownerName,
               o.status     as status
          from wms_owner o
         where o.id in
         <foreach collection="ownerIds" item="ownerId" open="(" separator="," close=")">#{ownerId}</foreach>
        </script>
        """)
    List<OwnerBindingPO> findByIds(@Param("ownerIds") Collection<Long> ownerIds);
}
