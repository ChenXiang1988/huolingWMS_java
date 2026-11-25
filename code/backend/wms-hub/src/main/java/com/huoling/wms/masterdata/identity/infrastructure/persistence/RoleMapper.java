package com.huoling.wms.masterdata.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RoleMapper extends BaseMapper<RolePO> {

    @Select("select role_id from wms_account_role where account_id = #{accountId}")
    List<Long> findRoleIdsByAccount(@Param("accountId") Long accountId);

    @Delete("delete from wms_account_role where account_id = #{accountId}")
    int deleteAccountRoles(@Param("accountId") Long accountId);

    @Insert("insert into wms_account_role(account_id, role_id) values(#{accountId}, #{roleId})")
    int insertAccountRole(@Param("accountId") Long accountId, @Param("roleId") Long roleId);

    @Select("""
        select p.perm_code
          from wms_role_permission rp
          join wms_permission p on p.id = rp.permission_id
         where rp.role_id = #{roleId}
           and p.status = 'ENABLED'
        """)
    List<String> findPermissionCodesByRole(@Param("roleId") Long roleId);

    /**
     * 乐观锁：只有版本号仍等于客户端提交值时才能推进，返回 0 即版本冲突。
     * 与后续写入在同一事务内，行锁保证并发下只有一个请求能推进版本。
     */
    @Update("""
        update wms_role
           set version = version + 1,
               updated_at = now()
         where id = #{roleId}
           and version = #{version}
        """)
    int advanceVersion(@Param("roleId") Long roleId, @Param("version") long version);

    @Delete("delete from wms_role_permission where role_id = #{roleId}")
    int deleteRolePermissions(@Param("roleId") Long roleId);

    @Insert("""
        insert into wms_role_permission(role_id, permission_id)
        select #{roleId}, id from wms_permission where perm_code = #{permCode}
        """)
    int insertRolePermission(@Param("roleId") Long roleId, @Param("permCode") String permCode);

    /** 绑定条数，含指向已停用权限点的绑定：有记录即视为已被配置过。 */
    @Select("select count(*) from wms_role_permission where role_id = #{roleId}")
    int countRolePermissions(@Param("roleId") Long roleId);

    /**
     * 幂等补绑定：已存在的关联不动，权限点不存在或已停用则整条 select 无行。
     * 重复执行只靠主键冲突兜底，不产生重复行（角色初始化可重复执行）。
     */
    @Insert("""
        insert into wms_role_permission(role_id, permission_id)
        select #{roleId}, p.id
          from wms_permission p
         where p.perm_code = #{permCode}
           and p.status = 'ENABLED'
        on conflict (role_id, permission_id) do nothing
        """)
    int insertRolePermissionIfAbsent(@Param("roleId") Long roleId, @Param("permCode") String permCode);

    /** 只写可变字段，不写 version：版本推进由 advanceVersion 负责（契约 API-012 的乐观锁）。 */
    @Update("""
        update wms_role
           set role_name = #{roleName},
               status = #{status},
               updated_at = now()
         where id = #{roleId}
        """)
    int updateProfile(@Param("roleId") Long roleId, @Param("roleName") String roleName,
                      @Param("status") String status);
}
