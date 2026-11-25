package com.huoling.wms.masterdata.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AcceptanceMapper extends BaseMapper<AcceptancePO> {

    /**
     * 按键索引受理单，命中后再由应用比对指纹：同键同指纹返回首次受理号，同键异体返回 COMMON_9005。
     * 不许把指纹拼进索引条件——那样异体请求会拿到新指纹从而绕过去重（契约幂等约定第 3 条）。
     */
    @Select("""
        select * from wms_acceptance
         where account_id = #{accountId}
           and api_code = #{apiCode}
           and idempotency_key = #{idempotencyKey}
         order by created_at asc
         limit 1
        """)
    AcceptancePO findByIdempotencyKey(@Param("accountId") Long accountId,
                                      @Param("apiCode") String apiCode,
                                      @Param("idempotencyKey") String idempotencyKey);

    @Select("select * from wms_acceptance where accept_no = #{acceptNo}")
    AcceptancePO findByAcceptNo(@Param("acceptNo") String acceptNo);
}
