package com.huoling.wms.masterdata.acceptance.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.acceptance.domain.Acceptance;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceRepository;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceStatus;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceSweep;
import com.huoling.wms.masterdata.acceptance.infrastructure.persistence.ArchiveAcceptanceMapper;
import com.huoling.wms.masterdata.acceptance.infrastructure.persistence.ArchiveAcceptancePO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * 受理记录仓储实现：唯一键（作用域、接口、幂等键）保证同一键只有一条受理记录。
 *
 * <p>同时实现超时扫描端口 {@link AcceptanceSweep}：两者读的是同一张表，
 * 分开写会多一份映射与连接配置。
 *
 * <p>跨业务隔离落在 SQL 谓词上：{@code api_code in (...)}，集合为空时不查出任何记录，
 * 不回落成全量（契约 1.6）。
 */
@Repository
public class AcceptanceRepositoryImpl implements AcceptanceRepository, AcceptanceSweep {

    private final ArchiveAcceptanceMapper mapper;

    public AcceptanceRepositoryImpl(ArchiveAcceptanceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Acceptance findByAcceptNo(String acceptNo, Set<String> apiCodes, Set<Long> subjectOwnerIds) {
        if (acceptNo == null || acceptNo.isBlank() || apiCodes == null || apiCodes.isEmpty()) {
            return null;
        }
        LambdaQueryWrapper<ArchiveAcceptancePO> wrapper = new LambdaQueryWrapper<ArchiveAcceptancePO>()
            .eq(ArchiveAcceptancePO::getAcceptNo, acceptNo)
            .in(ArchiveAcceptancePO::getApiCode, apiCodes);
        if (subjectOwnerIds != null) {
            if (subjectOwnerIds.isEmpty()) {
                // 指定列表为空即空域：一条都不许查出来，绝不回落成全量（INV-06、OI-08）。
                return null;
            }
            // NULL 的目标货主（建档受理单）天然落在 IN 之外：列表域账号看不到它（design「幂等与异步受理」）。
            wrapper.in(ArchiveAcceptancePO::getSubjectOwnerId, subjectOwnerIds);
        }
        return toDomain(mapper.selectOne(wrapper));
    }

    @Override
    public long countProcessing(Set<String> apiCodes) {
        if (apiCodes == null || apiCodes.isEmpty()) {
            return 0L;
        }
        Long count = mapper.countProcessing(apiCodes);
        return count == null ? 0L : count;
    }

    @Override
    public Acceptance findByIdempotency(String scopeKey, String apiCode, String idempotencyKey) {
        return toDomain(mapper.selectOne(new LambdaQueryWrapper<ArchiveAcceptancePO>()
            .eq(ArchiveAcceptancePO::getScopeKey, scopeKey)
            .eq(ArchiveAcceptancePO::getApiCode, apiCode)
            .eq(ArchiveAcceptancePO::getIdempotencyKey, idempotencyKey)));
    }

    @Override
    public void insert(Acceptance acceptance) {
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
        po.setSubjectOwnerId(acceptance.subjectOwnerId());
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

    private Acceptance toDomain(ArchiveAcceptancePO po) {
        if (po == null) {
            return null;
        }
        return new Acceptance(po.getId(), po.getAcceptNo(), po.getApiCode(), po.getScopeKey(),
            po.getAccountId() == null ? 0L : po.getAccountId(), po.getIdempotencyKey(),
            po.getStatusSnapshot(), AcceptanceStatus.valueOf(po.getAcceptStatus()),
            po.getResultJson(), po.getErrorCode(), po.getErrorMessage(), po.getSubjectOwnerId(),
            instant(po.getCreatedAt()), instant(po.getUpdatedAt()));
    }

    private OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
