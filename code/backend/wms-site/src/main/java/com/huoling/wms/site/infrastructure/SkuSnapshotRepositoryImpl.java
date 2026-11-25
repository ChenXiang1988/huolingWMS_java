package com.huoling.wms.site.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.domain.SkuListSortKey;
import com.huoling.wms.site.domain.SitePageCursor;
import com.huoling.wms.site.domain.SkuSnapshot;
import com.huoling.wms.site.domain.SkuSnapshotRepository;
import com.huoling.wms.site.infrastructure.persistence.SkuSnapshotMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 本仓缓存仓储实现：读路径一律带 {@code warehouse_id}，不提供跨仓重载（INV-06）。 */
@Repository
public class SkuSnapshotRepositoryImpl implements SkuSnapshotRepository {

    private final SkuSnapshotMapper mapper;

    public SkuSnapshotRepositoryImpl(SkuSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SkuSnapshot> find(Long siteId, String archiveNo) {
        if (siteId == null || archiveNo == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(toDomain(mapper.find(siteId, archiveNo)));
    }

    @Override
    public List<SkuSnapshot> page(SnapshotQuery query) {
        SitePageCursor cursor = query.cursor();
        // 游标值按排序字段取一种形态：时间型给时间参数，文本型给文本参数，另一侧留空。
        OffsetDateTime cursorTime = null;
        String cursorText = null;
        if (cursor != null) {
            if (cursor.key() == SkuListSortKey.UPDATE_TIME) {
                cursorTime = SqlValues.offset(cursor.instantValue());
            } else {
                cursorText = cursor.textValue();
            }
        }
        return mapper.page(query.siteId(), query.ownerCode(), query.name(), query.category(),
                SqlValues.offset(query.updatedFrom()), SqlValues.offset(query.updatedTo()),
                query.sort().keyName(), query.sort().ascending(),
                cursor == null ? null : cursor.id(), cursorTime, cursorText,
                query.limit()).stream().map(this::toDomain).toList();
    }

    @Override
    public void apply(SkuSnapshot snapshot) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", snapshot.id() == null ? IdWorker.getId() : snapshot.id());
        row.put("siteId", snapshot.siteId());
        row.put("ownerId", snapshot.ownerId());
        row.put("archiveId", snapshot.archiveId());
        row.put("archiveNo", snapshot.archiveNo());
        row.put("ownerCode", snapshot.ownerCode());
        row.put("name", snapshot.name());
        row.put("barcode", snapshot.barcode());
        row.put("baseUnit", snapshot.baseUnit());
        row.put("storageCondition", snapshot.storageCondition());
        row.put("category", snapshot.category());
        row.put("specification", snapshot.specification());
        row.put("status", snapshot.status());
        row.put("version", snapshot.version());
        row.put("eventId", snapshot.eventId());
        row.put("dataTimestamp", SqlValues.offset(snapshot.dataTimestamp()));
        row.put("appliedAt", SqlValues.offset(snapshot.appliedAt() == null
            ? Instant.now() : snapshot.appliedAt()));
        mapper.upsert(row);
    }

    private SkuSnapshot toDomain(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return new SkuSnapshot(SqlValues.id(row, "id"), SqlValues.id(row, "siteId"),
            SqlValues.id(row, "ownerId"), SqlValues.id(row, "archiveId"),
            SqlValues.text(row, "archiveNo"), SqlValues.text(row, "ownerCode"),
            SqlValues.text(row, "name"), SqlValues.text(row, "barcode"),
            SqlValues.text(row, "baseUnit"), SqlValues.text(row, "storageCondition"),
            SqlValues.text(row, "category"), SqlValues.text(row, "specification"),
            SqlValues.text(row, "status"), SqlValues.longValue(row, "version", 0L),
            SqlValues.text(row, "eventId"), SqlValues.instant(row, "dataTimestamp"),
            SqlValues.instant(row, "appliedAt"));
    }
}
