package com.huoling.wms.masterdata.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleScopeMapper extends BaseMapper<RoleScopePO> {

    @Select("select * from wms_role_scope where role_id = #{roleId}")
    List<RoleScopePO> findByRole(@Param("roleId") Long roleId);

    @Select("select target_id from wms_role_scope_item where role_scope_id = #{scopeId}")
    List<Long> findTargetIds(@Param("scopeId") Long scopeId);

    @Delete("delete from wms_role_scope_item where role_scope_id = #{scopeId}")
    int deleteItems(@Param("scopeId") Long scopeId);

    @Delete("delete from wms_role_scope where role_id = #{roleId}")
    int deleteByRole(@Param("roleId") Long roleId);

    @Insert("insert into wms_role_scope_item(id, role_scope_id, target_id) values(#{id}, #{scopeId}, #{targetId})")
    int insertItem(@Param("id") Long id, @Param("scopeId") Long scopeId, @Param("targetId") Long targetId);
}
