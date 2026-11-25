package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;

import java.time.Instant;

/**
 * 审核留痕条目（只增不改）。审核通过时与状态变更、版本递增、下行消息在同一本地事务里写入。
 */
public record SkuReviewRecord(
        Long id,
        Long archiveId,
        String archiveNo,
        Long ownerId,
        SkuArchiveAction action,
        SkuArchiveStatus fromStatus,
        SkuArchiveStatus toStatus,
        String reason,
        long version,
        long operatorId,
        Instant createdAt) {
}
