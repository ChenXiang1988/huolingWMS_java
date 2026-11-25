package com.huoling.wms.masterdata.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PermissionMapper extends BaseMapper<PermissionPO> {

    @Select("""
        select distinct p.perm_code
          from wms_permission p
          join wms_role_permission rp on rp.permission_id = p.id
          join wms_account_role ar on ar.role_id = rp.role_id
          join wms_role r on r.id = ar.role_id
         where ar.account_id = #{accountId}
           and p.status = 'ENABLED'
           and r.status = 'ENABLED'
           and r.applies_to_client = #{client}
        """)
    List<String> findCodesByAccount(@Param("accountId") Long accountId, @Param("client") String client);
}
