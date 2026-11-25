package com.huoling.wms.masterdata.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthorizationLogMapper extends BaseMapper<AuthorizationLogPO> {
}
