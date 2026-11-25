package com.huoling.wms.masterdata.identity.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AuthorizationLogMapper;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AuthorizationLogPO;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 授权审计：谁、对谁、改了什么、变更前后。只追加，不修改（19）。
 */
@Service
public class AuditService {

    private final AuthorizationLogMapper mapper;

    public AuditService(AuthorizationLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(Long operatorId, Long targetAccountId, String action,
                       String before, String after) {
        AuthorizationLogPO po = new AuthorizationLogPO();
        po.setId(IdWorker.getId());
        po.setOperatorId(operatorId);
        po.setTargetAccountId(targetAccountId);
        po.setAction(action);
        po.setBeforeJson(before);
        po.setAfterJson(after);
        po.setCreatedAt(OffsetDateTime.now());
        mapper.insert(po);
    }
}
