package com.huoling.wms.masterdata.acceptance.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.acceptance.domain.IdempotencyRecord;
import com.huoling.wms.masterdata.acceptance.domain.IdempotencyRepository;
import com.huoling.wms.masterdata.acceptance.domain.IdempotencyStatus;
import com.huoling.wms.masterdata.acceptance.infrastructure.persistence.IdempotencyMapper;
import com.huoling.wms.masterdata.acceptance.infrastructure.persistence.IdempotencyPO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 幂等记录仓储实现：占位靠唯一键（作用域、接口、键），并发同键只放行一条。 */
@Repository
public class IdempotencyRepositoryImpl implements IdempotencyRepository {

    private final IdempotencyMapper mapper;

    public IdempotencyRepositoryImpl(IdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public IdempotencyRecord find(String scopeKey, String apiCode, String idempotencyKey) {
        IdempotencyPO po = mapper.selectOne(wrapper(scopeKey, apiCode, idempotencyKey));
        if (po == null) {
            return null;
        }
        return new IdempotencyRecord(po.getFingerprint(), IdempotencyStatus.valueOf(po.getStatus()),
            po.getResultJson(), po.getExpiresAt() == null ? null : po.getExpiresAt().toInstant());
    }

    @Override
    public boolean reserve(String scopeKey, String apiCode, String idempotencyKey, String fingerprint,
                           Instant createdAt, Instant expiresAt) {
        IdempotencyPO po = new IdempotencyPO();
        po.setId(IdWorker.getId());
        po.setScopeKey(scopeKey);
        po.setApiCode(apiCode);
        po.setIdempotencyKey(idempotencyKey);
        po.setFingerprint(fingerprint);
        po.setStatus(IdempotencyStatus.IN_PROGRESS.name());
        po.setCreatedAt(offset(createdAt));
        po.setExpiresAt(offset(expiresAt));
        // 占位撞唯一键时数据库不报错（on conflict do nothing），事务不被置为 aborted，
        // 调用方可以继续读首次记录并按契约回放受理号或返回 IDEMPOTENT_IN_PROGRESS。
        return mapper.insertIfAbsent(po) > 0;
    }

    @Override
    public void markSucceeded(String scopeKey, String apiCode, String idempotencyKey, String resultJson) {
        mapper.markSucceeded(scopeKey, apiCode, idempotencyKey, resultJson);
    }

    @Override
    public void release(String scopeKey, String apiCode, String idempotencyKey) {
        mapper.release(scopeKey, apiCode, idempotencyKey);
    }

    private LambdaQueryWrapper<IdempotencyPO> wrapper(String scopeKey, String apiCode,
                                                      String idempotencyKey) {
        return new LambdaQueryWrapper<IdempotencyPO>()
            .eq(IdempotencyPO::getScopeKey, scopeKey)
            .eq(IdempotencyPO::getApiCode, apiCode)
            .eq(IdempotencyPO::getIdempotencyKey, idempotencyKey);
    }

    private OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
