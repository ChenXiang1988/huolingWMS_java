package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.application.ChangeRequestService;
import com.huoling.wms.masterdata.archive.domain.ChangeRequest;

import java.time.Instant;
import java.util.List;

/**
 * 变更申请条目视图（API-012、API-014）：申请内容、状态与驳回原因。
 *
 * <p>字段英文名以契约「字段结构与枚举」为准：{@code archiveId} 为档案系统编号，{@code siteId} 为本仓标识，
 * {@code changeField} 为契约闭集里的小驼峰名。单仓端只看到本仓申请，跨仓按越权拒绝（INV-19）。
 */
public record ChangeRequestView(
        String requestNo,
        String archiveId,
        String ownerId,
        String siteId,
        String changeField,
        String targetValue,
        String reason,
        String status,
        String reviewReason,
        Long archiveVersion,
        Instant createdAt,
        Instant updatedAt) {

    static ChangeRequestView of(ChangeRequest request) {
        return new ChangeRequestView(request.requestNo(), request.archiveNo(),
            ArchiveIdentifiers.toWire(request.ownerId()),
            ArchiveIdentifiers.toWire(request.warehouseId()),
            ChangeFieldNames.wire(request.field()),
            request.targetValue(), request.reason(), request.status().name(),
            request.reviewReason(), request.archiveVersion(), request.createdAt(),
            request.updatedAt());
    }

    /** 列表响应：条目、下一页游标、是否还有数据与数据时间戳。 */
    public record ChangeRequestListResponse(List<ChangeRequestView> items, String nextCursor,
                                            boolean hasMore, Instant dataTimestamp) {

        static ChangeRequestListResponse of(ChangeRequestService.ChangeRequestPage page) {
            return new ChangeRequestListResponse(
                page.items().stream().map(ChangeRequestView::of).toList(),
                page.nextCursor(), page.hasMore(), page.dataTimestamp());
        }
    }
}
