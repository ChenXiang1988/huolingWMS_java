package com.huoling.wms.masterdata.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountMapper extends BaseMapper<AccountPO> {

    @Select("""
        select count(*)
          from wms_account a
          join wms_account_role ar on ar.account_id = a.id
          join wms_role r on r.id = ar.role_id
         where r.role_code = #{roleCode}
           and a.status = 'ENABLED'
           and r.status = 'ENABLED'
        """)
    long countEnabledByRoleCode(@Param("roleCode") String roleCode);

    /**
     * 乐观锁：只有版本号仍等于客户端提交值时才能推进，返回 0 即版本冲突。
     * 与后续写入在同一事务内，行锁保证并发下只有一个请求能推进版本。
     */
    @Update("""
        update wms_account
           set version = version + 1,
               updated_at = now()
         where id = #{accountId}
           and version = #{version}
        """)
    int advanceVersion(@Param("accountId") Long accountId, @Param("version") long version);

    /** 某货主下的全部账号标识：停用货主后按它逐个撤销刷新令牌（契约 1.7）。 */
    @Select("""
        select id
          from wms_account
         where owner_id = #{ownerId}
         order by id asc
        """)
    java.util.List<Long> findIdsByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * 只写状态与密码散列，不写 version：版本由同事务内的 advanceVersion 推进，
     * 避免把加载时的旧版本号回写覆盖（契约 API-008 的乐观锁）。
     */
    @Update("""
        update wms_account
           set status = #{status},
               password_hash = #{passwordHash},
               updated_at = now()
         where id = #{accountId}
        """)
    int updateProfile(@Param("accountId") Long accountId, @Param("status") String status,
                      @Param("passwordHash") String passwordHash);
}
