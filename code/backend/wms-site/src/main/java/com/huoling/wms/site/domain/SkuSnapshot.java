package com.huoling.wms.site.domain;

import java.time.Instant;

/**
 * 本仓生效档案缓存条目（{@code site_sku_snapshot}）。
 *
 * <p>只读副本，权威源在 Hub（D12）；{@code version} 是端间一致性基准，
 * {@code dataTimestamp} 是权威数据的产生时间，降级展示必须带上它，不把缓存伪装成最新结果。
 *
 * <p>{@code archiveNo} 是全局唯一系统编号，也是三端对外唯一的档案标识；
 * {@code archiveId} 是表上 NOT NULL 的数值列（上一轮脚本已合入不可改），
 * 由系统编号的稳定摘要填充，仅作占位，不参与任何关联。
 */
public record SkuSnapshot(
        Long id,
        Long siteId,
        Long ownerId,
        Long archiveId,
        String archiveNo,
        String ownerCode,
        String name,
        String barcode,
        String baseUnit,
        String storageCondition,
        String category,
        String specification,
        String status,
        long version,
        String eventId,
        Instant dataTimestamp,
        Instant appliedAt) {

    /** 是否可被单据与库存引用：缓存里只有生效与停用两态。 */
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
