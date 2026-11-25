package com.huoling.wms.masterdata.archive.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.archive.domain.AcceptanceStatus;
import com.huoling.wms.masterdata.archive.domain.ArchiveAcceptance;
import com.huoling.wms.masterdata.archive.domain.ArchiveAcceptanceRepository;
import com.huoling.wms.masterdata.archive.domain.ArchiveAcceptanceSweep;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.ArchiveAcceptanceMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.ArchiveAcceptancePO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 受理记录仓储实现：唯一键（作用域、接口、幂等键）保证同一键只有一条受理记录。
 *
 * <p>同时实现超时扫描端口 {@link ArchiveAcceptanceSweep}：两者读的是同一张表，
 * 分开写会多一份映射与连接配置。
 */
@Repository
public class ArchiveAcceptanceRepositoryImpl implements ArchiveAcceptanceRepository,
    ArchiveAcceptanceSweep {

    private final ArchiveAcceptanceMapper mapper;

    public ArchiveAcceptanceRepositoryImpl(ArchiveAcceptanceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ArchiveAcceptance findByAcceptNo(String acceptNo) {
        if (acceptNo == null || acceptNo.isBlank()) {
            return null;
        }
        return toDomain(mapper.selectOne(new LambdaQueryWrapper<ArchiveAcceptancePO>()
            .eq(ArchiveAcceptancePO::getAcceptNo, acceptNo)));
    }

    @Override
    public ArchiveAcceptance findByIdempotency(String scopeKey, String apiCode, String idempotencyKey) {
        return toDomain(mapper.selectOne(new LambdaQueryWrapper<ArchiveAcceptancePO>()
            .eq(ArchiveAcceptancePO::getScopeKey, scopeKey)
            .eq(ArchiveAcceptancePO::getApiCode, apiCode)
            .eq(ArchiveAcceptancePO::getIdempotencyKey, idempotencyKey)));
    }

    @Override
    public void insert(ArchiveAcceptance acceptance) {
        ArchiveAcceptancePO po = new ArchiveAcceptancePO();
        po.setId(acceptance.id() == null ? IdWorker.getId() : acceptance.id());
        po.setAcceptNo(acceptance.acceptNo());
        po.setApiCode(acceptance.apiCode());
        po.setScopeKey(acceptance.scopeKey());
        po.setAccountId(acceptance.accountId());
        po.setIdempotencyKey(acceptance.idempotencyKey());
        po.setStatusSnapshot(acceptance.statusSnapshot());
        po.setAcceptStatus(acceptance.status().name());
        po.setResultJson(acceptance.resultJson());
        po.setErrorCode(acceptance.errorCode());
        po.setErrorMessage(acceptance.errorMessage());
        po.setCreatedAt(offset(acceptance.createdAt()));
        po.setUpdatedAt(offset(acceptance.updatedAt()));
        mapper.insert(po);
    }

    @Override
    public void markSucceeded(String acceptNo, String resultJson, Instant now) {
        mapper.markSucceeded(acceptNo, resultJson, offset(now));
    }

    @Override
    public void markFailed(String acceptNo, String errorCode, String errorMessage, Instant now) {
        mapper.markFailed(acceptNo, errorCode, errorMessage, offset(now));
    }

    @Override
    public List<String> findProcessingBefore(Instant deadline, int limit) {
        return mapper.findProcessingBefore(offset(deadline), limit);
    }

    private ArchiveAcceptance toDomain(ArchiveAcceptancePO po) {
        if (po == null) {
            return null;
        }
        return new ArchiveAcceptance(po.getId(), po.getAcceptNo(), po.getApiCode(), po.getScopeKey(),
            po.getAccountId() == null ? 0L : po.getAccountId(), po.getIdempotencyKey(),
            po.getStatusSnapshot(), AcceptanceStatus.valueOf(po.getAcceptStatus()),
            po.getResultJson(), po.getErrorCode(), po.getErrorMessage(),
            instant(po.getCreatedAt()), instant(po.getUpdatedAt()));
    }

    private OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
