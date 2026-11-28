package com.huoling.wms.site.infrastructure;

import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.site.domain.SiteOwnerStatus;
import com.huoling.wms.site.domain.SiteOwnerStatusRepository;
import com.huoling.wms.site.infrastructure.persistence.SiteOwnerStatusMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 货主状态副本仓储实现：一条按主键查、一条按版本号单调 upsert。
 *
 * <p>写入是否生效以库的条件更新为准（受影响行数为 1 才算应用），
 * 不在应用层先读后比：先读后比在并发消费下会把旧事件写回去。
 */
@Repository
public class SiteOwnerStatusRepositoryImpl implements SiteOwnerStatusRepository {

    private final SiteOwnerStatusMapper mapper;

    public SiteOwnerStatusRepositoryImpl(SiteOwnerStatusMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SiteOwnerStatus> find(long ownerId) {
        return Optional.ofNullable(toDomain(mapper.find(ownerId)));
    }

    @Override
    public boolean apply(SiteOwnerStatus status) {
        Map<String, Object> row = new HashMap<>();
        row.put("ownerId", status.ownerId());
        row.put("ownerNo", status.ownerNo());
        row.put("status", status.status() == null ? null : status.status().name());
        row.put("sourceVersion", status.sourceVersion());
        row.put("eventId", status.eventId());
        row.put("dataTimestamp", SqlValues.offset(status.dataTimestamp()));
        row.put("updatedAt", SqlValues.offset(status.updatedAt() == null
            ? Instant.now() : status.updatedAt()));
        return mapper.upsert(row) == 1;
    }

    private SiteOwnerStatus toDomain(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return new SiteOwnerStatus(
            SqlValues.id(row, "ownerId"),
            SqlValues.text(row, "ownerNo"),
            OwnerStatus.valueOf(SqlValues.text(row, "status")),
            SqlValues.longValue(row, "sourceVersion", 0L),
            SqlValues.text(row, "eventId"),
            SqlValues.instant(row, "dataTimestamp"),
            SqlValues.instant(row, "updatedAt"));
    }
}
