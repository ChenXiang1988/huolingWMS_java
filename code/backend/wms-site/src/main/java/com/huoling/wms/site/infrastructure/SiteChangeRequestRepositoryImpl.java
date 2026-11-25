package com.huoling.wms.site.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.site.domain.SiteChangeRequest;
import com.huoling.wms.site.domain.SiteChangeRequestRepository;
import com.huoling.wms.site.domain.SitePageCursor;
import com.huoling.wms.site.infrastructure.persistence.SiteChangeRequestMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 单仓变更申请仓储实现。 */
@Repository
public class SiteChangeRequestRepositoryImpl implements SiteChangeRequestRepository {

    private final SiteChangeRequestMapper mapper;

    public SiteChangeRequestRepositoryImpl(SiteChangeRequestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean hasPending(Long siteId, String archiveId) {
        return mapper.countPending(siteId, archiveId) > 0;
    }

    @Override
    public void insert(SiteChangeRequest request) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", request.id() == null ? IdWorker.getId() : request.id());
        row.put("requestNo", request.requestNo());
        row.put("acceptNo", request.acceptNo());
        row.put("siteId", request.siteId());
        row.put("archiveId", request.archiveId());
        row.put("archiveVersion", request.archiveVersion());
        row.put("ownerId", request.ownerId());
        row.put("changeField", request.changeField());
        row.put("targetValue", request.targetValue());
        row.put("reason", request.reason());
        row.put("status", request.status());
        row.put("reviewReason", request.reviewReason());
        row.put("archiveReviewedVersion", request.archiveReviewedVersion());
        row.put("lastEventId", request.lastEventId());
        row.put("uploadStatus", request.uploadStatus());
        row.put("uploadAttempts", request.uploadAttempts());
        row.put("lastError", request.lastError());
        row.put("nextRetryAt", SqlValues.offset(request.nextRetryAt()));
        row.put("createdAt", SqlValues.offset(request.createdAt()));
        row.put("updatedAt", SqlValues.offset(request.updatedAt()));
        mapper.insert(row);
    }

    @Override
    public List<SiteChangeRequest> page(SiteChangeRequestQuery query) {
        SitePageCursor cursor = query.cursor();
        // 游标值按排序字段取一种形态：时间、数值、文本各一路，其余留空，SQL 只挑用得到的那一路。
        OffsetDateTime cursorTime = null;
        Long cursorNumber = null;
        String cursorText = null;
        if (cursor != null) {
            switch (cursor.key()) {
                case OWNER_CODE -> cursorNumber = cursor.longValue();
                case NAME -> cursorText = cursor.textValue();
                default -> cursorTime = SqlValues.offset(cursor.instantValue());
            }
        }
        return mapper.page(query.siteId(), query.status(), query.requestNo(), query.archiveId(),
                query.sort().keyName(), query.sort().ascending(),
                cursor == null ? null : cursor.id(), cursorTime, cursorNumber, cursorText,
                query.limit()).stream().map(this::toDomain).toList();
    }

    @Override
    public List<SiteChangeRequest> findUploadable(int limit, Instant now) {
        return mapper.findUploadable(limit, SqlValues.offset(now)).stream().map(this::toDomain).toList();
    }

    @Override
    public void markUploaded(String requestNo, Instant now) {
        mapper.markUploaded(requestNo, SqlValues.offset(now));
    }

    @Override
    public void markUploadFailed(String requestNo, String error, Instant now) {
        String trimmed = error == null ? null : (error.length() > 256 ? error.substring(0, 256) : error);
        mapper.markUploadFailed(requestNo, trimmed, SqlValues.offset(now));
    }

    @Override
    public void applyReviewResult(String requestNo, String status, String reviewReason,
                                  Long archiveReviewedVersion, String eventId, Instant now) {
        mapper.applyReviewResult(requestNo, status, reviewReason, archiveReviewedVersion, eventId,
            SqlValues.offset(now));
    }

    @Override
    public Optional<SiteChangeRequest> findByRequestNo(Long siteId, String requestNo) {
        return Optional.ofNullable(toDomain(mapper.findByRequestNo(siteId, requestNo)));
    }

    private SiteChangeRequest toDomain(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return new SiteChangeRequest(SqlValues.id(row, "id"), SqlValues.text(row, "requestNo"),
            SqlValues.text(row, "acceptNo"), SqlValues.id(row, "siteId"),
            SqlValues.text(row, "archiveId"), SqlValues.longValue(row, "archiveVersion", 0L),
            SqlValues.id(row, "ownerId"), SqlValues.text(row, "changeField"),
            SqlValues.text(row, "targetValue"), SqlValues.text(row, "reason"),
            SqlValues.text(row, "status"), SqlValues.text(row, "reviewReason"),
            SqlValues.id(row, "archiveReviewedVersion"), SqlValues.text(row, "lastEventId"),
            SqlValues.text(row, "uploadStatus"), SqlValues.intValue(row, "uploadAttempts", 0),
            SqlValues.text(row, "lastError"), SqlValues.instant(row, "nextRetryAt"),
            SqlValues.instant(row, "createdAt"), SqlValues.instant(row, "updatedAt"));
    }
}
