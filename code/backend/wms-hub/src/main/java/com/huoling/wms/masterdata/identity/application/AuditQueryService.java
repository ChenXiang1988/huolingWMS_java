package com.huoling.wms.masterdata.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AuthorizationLogMapper;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AuthorizationLogPO;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 授权审计查询（API-017）。
 */
@Service
public class AuditQueryService {

    private final AuthorizationLogMapper mapper;

    public AuditQueryService(AuthorizationLogMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult list(Long targetAccountId, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        LambdaQueryWrapper<AuthorizationLogPO> wrapper = new LambdaQueryWrapper<AuthorizationLogPO>()
            .eq(targetAccountId != null, AuthorizationLogPO::getTargetAccountId, targetAccountId)
            .ge(from != null, AuthorizationLogPO::getCreatedAt, from)
            .le(to != null, AuthorizationLogPO::getCreatedAt, to)
            .orderByDesc(AuthorizationLogPO::getCreatedAt);
        var result = mapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<LogView> items = result.getRecords().stream()
            .map(po -> new LogView(po.getId(), po.getOperatorId(), po.getTargetAccountId(),
                po.getAction(), po.getBeforeJson(), po.getAfterJson(), po.getCreatedAt()))
            .toList();
        return new PageResult(items, result.getTotal(), safePage, safeSize);
    }

    public record LogView(Long id, Long operatorId, Long targetAccountId, String action,
                          String beforeJson, String afterJson, OffsetDateTime createdAt) {
    }

    public record PageResult(List<LogView> items, long total, int page, int size) {
    }
}
