package com.huoling.wms.site.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.site.domain.AcceptanceState;
import com.huoling.wms.site.domain.SiteAcceptance;
import com.huoling.wms.site.domain.SiteAcceptanceRepository;
import com.huoling.wms.site.domain.SiteAcceptanceSweep;
import com.huoling.wms.site.infrastructure.persistence.SiteAcceptanceMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单仓受理记录仓储实现。
 *
 * <p>同时实现超时扫描端口 {@link SiteAcceptanceSweep}：同一张表的两种读法，
 * 分开写会多一份映射与依赖。
 */
@Repository
public class SiteAcceptanceRepositoryImpl implements SiteAcceptanceRepository, SiteAcceptanceSweep {

    private final SiteAcceptanceMapper mapper;

    public SiteAcceptanceRepositoryImpl(SiteAcceptanceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SiteAcceptance findByIdempotency(Long siteId, String apiCode, String idempotencyKey) {
        return toDomain(mapper.findByIdempotency(siteId, apiCode, idempotencyKey));
    }

    @Override
    public SiteAcceptance findByAcceptNo(Long siteId, String acceptNo) {
        return toDomain(mapper.findByAcceptNo(siteId, acceptNo));
    }

    @Override
    public void insert(SiteAcceptance acceptance) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", acceptance.id() == null ? IdWorker.getId() : acceptance.id());
        row.put("acceptNo", acceptance.acceptNo());
        row.put("siteId", acceptance.siteId());
        row.put("apiCode", acceptance.apiCode());
        row.put("idempotencyKey", acceptance.idempotencyKey());
        row.put("fingerprint", acceptance.fingerprint());
        row.put("statusSnapshot", acceptance.statusSnapshot());
        row.put("acceptStatus", acceptance.acceptStatus().name());
        row.put("resultJson", acceptance.resultJson());
        row.put("errorCode", acceptance.errorCode());
        row.put("errorMessage", acceptance.errorMessage());
        row.put("createdAt", SqlValues.offset(acceptance.createdAt()));
        row.put("updatedAt", SqlValues.offset(acceptance.updatedAt()));
        mapper.insert(row);
    }

    @Override
    public void markSucceeded(String acceptNo, String resultJson, Instant now) {
        mapper.markSucceeded(acceptNo, resultJson, SqlValues.offset(now));
    }

    @Override
    public void markFailed(String acceptNo, String errorCode, String errorMessage, Instant now) {
        mapper.markFailed(acceptNo, errorCode, errorMessage, SqlValues.offset(now));
    }

    @Override
    public List<String> findProcessingBefore(Instant deadline, int limit) {
        return mapper.findProcessingBefore(SqlValues.offset(deadline), limit);
    }

    private SiteAcceptance toDomain(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return new SiteAcceptance(SqlValues.id(row, "id"), SqlValues.text(row, "acceptNo"),
            SqlValues.id(row, "siteId"), SqlValues.text(row, "apiCode"),
            SqlValues.text(row, "idempotencyKey"), SqlValues.text(row, "fingerprint"),
            SqlValues.text(row, "statusSnapshot"),
            AcceptanceState.valueOf(SqlValues.text(row, "acceptStatus")),
            SqlValues.text(row, "resultJson"), SqlValues.text(row, "errorCode"),
            SqlValues.text(row, "errorMessage"), SqlValues.instant(row, "createdAt"),
            SqlValues.instant(row, "updatedAt"));
    }
}
