package com.huoling.wms.site.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.site.domain.SiteChangeRequest;
import com.huoling.wms.site.domain.SiteChangeRequestRepository;
import com.huoling.wms.site.domain.SiteException;
import com.huoling.wms.site.domain.SitePageCursor;
import com.huoling.wms.site.domain.SkuSnapshot;
import com.huoling.wms.site.domain.SkuSnapshotRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 单仓端变更申请（API-011 发起、API-014 查询）。
 *
 * <p>单仓永远不改主数据：这里只写本仓记录与上行进度，权威表由 Hub 写入（D12、specs product-archive-review）。
 * 发起前先按本仓缓存校验「档案在本仓可见、处于生效态、同一档案没有待审核申请」，
 * 全部属于入口同步校验，失败不产生受理记录（契约 API-011 错误码：{@code NOT_FOUND_ARCHIVE}、
 * {@code STATE_INVALID}、{@code FORBIDDEN_SITE}）。
 */
@Service
public class SiteChangeRequestService {

    private final SiteChangeRequestRepository changeRequests;
    private final SkuSnapshotRepository snapshots;
    private final SiteNumbers numbers;

    public SiteChangeRequestService(SiteChangeRequestRepository changeRequests,
                                    SkuSnapshotRepository snapshots,
                                    SiteNumbers numbers) {
        this.changeRequests = changeRequests;
        this.snapshots = snapshots;
        this.numbers = numbers;
    }

    /**
     * API-011：本仓发起字段变更申请。
     *
     * @param siteId  令牌里的唯一本仓标识
     * @param acceptNo 已落库的受理号，回写到申请记录便于双向追溯
     */
    public CreatedView create(Long siteId, String archiveId, String changeField, String targetValue,
                              String reason, String acceptNo) {
        String field = SiteChangeFields.require(changeField);
        requireText(archiveId, "archiveId");
        requireText(targetValue, "targetValue");
        requireText(reason, "reason");

        SkuSnapshot snapshot = snapshots.find(siteId, archiveId)
            .orElseThrow(() -> new SiteException(ArchiveContractCodes.NOT_FOUND_ARCHIVE,
                "该档案不在本仓可见范围内，拒绝发起变更申请"));
        if (!snapshot.active()) {
            throw new SiteException(ArchiveContractCodes.STATE_INVALID,
                "档案状态 " + snapshot.status() + " 不接受变更申请");
        }
        if (changeRequests.hasPending(siteId, archiveId)) {
            throw new SiteException(ArchiveContractCodes.STATE_INVALID,
                "该档案已有待审核的变更申请，需先完成审核");
        }

        Instant now = Instant.now();
        SiteChangeRequest request = SiteChangeRequest.accepted(numbers.nextRequestNo(), acceptNo,
            siteId, archiveId, snapshot.version(), snapshot.ownerId(), field, targetValue, reason, now);
        try {
            changeRequests.insert(request);
        } catch (DataIntegrityViolationException e) {
            // 部分唯一索引兜底：并发发起只成功一条，其余按状态冲突返回。
            throw new SiteException(ArchiveContractCodes.STATE_INVALID,
                "该档案已有待审核的变更申请，需先完成审核");
        }
        return new CreatedView(request.requestNo(), request.status());
    }

    /** API-014：本仓申请列表，含驳回原因，可按档案过滤，按申请字段排序。 */
    public ChangeRequestPage page(Long siteId, String status, String requestNo, String archiveId,
                                  SkuListSort sort, String cursor, int limit) {
        if (limit <= 0 || limit > SiteChangeRequestRepository.SiteChangeRequestQuery.MAX_LIMIT) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + SiteChangeRequestRepository.SiteChangeRequestQuery.MAX_LIMIT);
        }
        List<SiteChangeRequest> rows = changeRequests.page(
            new SiteChangeRequestRepository.SiteChangeRequestQuery(siteId,
                requireStatus(status), blankToNull(requestNo), blankToNull(archiveId), sort,
                SitePageCursor.decode(cursor, sort), limit + 1));
        boolean hasMore = rows.size() > limit;
        List<SiteChangeRequest> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !items.isEmpty()
            ? cursorOf(items.get(items.size() - 1), sort).encode()
            : null;
        Instant dataTimestamp = items.isEmpty() ? Instant.now() : items.get(0).updatedAt();
        return new ChangeRequestPage(items.stream().map(ChangeRequestView::of).toList(), nextCursor,
            hasMore, dataTimestamp);
    }

    /**
     * 下一页锚点：取本页最后一条在当前排序字段上的值。
     *
     * <p>排序字段按申请字段取值（契约字段结构表）：时间取申请创建时间，货主编码取申请归属货主，
     * 名称取申请的变更字段，与仓储层的 ORDER BY 是同一列。
     */
    private SitePageCursor cursorOf(SiteChangeRequest request, SkuListSort sort) {
        return switch (sort.key()) {
            case OWNER_CODE -> SitePageCursor.ofNumber(sort, request.ownerId(), request.id());
            case NAME -> SitePageCursor.ofText(sort, request.changeField(), request.id());
            case UPDATE_TIME -> SitePageCursor.ofTime(sort, request.createdAt(), request.id());
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 状态闭集校验：未知取值按参数错误拒绝（契约「枚举约定」）。 */
    private String requireStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PENDING", "APPROVED", "REJECTED" -> status.trim().toUpperCase(java.util.Locale.ROOT);
            default -> throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "参数 status 取值不合法：收到 " + status + "，只接受 PENDING、APPROVED、REJECTED");
        };
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID, "字段 " + field + " 必填");
        }
    }

    /**
     * API-011 的受理结果摘要：申请编号与申请状态。
     *
     * <p>线上字段名取契约「字段结构与枚举」的 {@code result.requestId}（不是内部命名的
     * {@code requestNo}）：受理面板按受理号查到的结果里要能直接拿到申请编号。
     * Java 访问器名保持 {@code requestNo()}，内部调用点与列表视图不受影响。
     */
    public record CreatedView(
            @JsonProperty("requestId") String requestNo,
            String status) {
    }

    /** API-014 条目视图：申请内容、状态、驳回原因与发起时的缓存版本。 */
    public record ChangeRequestView(String requestNo, String archiveId, String siteId,
                                    String changeField, String targetValue, String reason,
                                    String status, String reviewReason, Long archiveVersion,
                                    String uploadStatus, Instant createdAt, Instant updatedAt) {

        static ChangeRequestView of(SiteChangeRequest request) {
            return new ChangeRequestView(request.requestNo(), request.archiveId(),
                String.valueOf(request.siteId()), request.changeField(), request.targetValue(),
                request.reason(), request.status(), request.reviewReason(),
                request.archiveReviewedVersion(), request.uploadStatus(), request.createdAt(),
                request.updatedAt());
        }
    }

    /** API-014 列表结果。 */
    public record ChangeRequestPage(List<ChangeRequestView> items, String nextCursor,
                                    boolean hasMore, Instant dataTimestamp) {
    }
}
