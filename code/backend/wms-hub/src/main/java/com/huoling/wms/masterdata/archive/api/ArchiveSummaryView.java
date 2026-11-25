package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.domain.SkuArchive;

import java.time.Instant;

/**
 * 列表条目视图（API-005、API-007）：档案摘要加版本号与数据时间戳。
 *
 * <p>字段英文名以契约「字段结构与枚举」为准（{@code spec} 为规格型号，{@code archiveId} 为系统编号）；
 * 标识一律十进制字符串。货主端只看到自己名下的档案，不出现单仓部署信息或库位号
 * （specs product-archive-access）。
 */
public record ArchiveSummaryView(
        String archiveId,
        String ownerId,
        String ownerCode,
        String name,
        String barcode,
        String baseUnit,
        String storageCondition,
        String category,
        String spec,
        String brand,
        String status,
        long version,
        Instant dataTimestamp,
        Instant updatedAt) {

    public static ArchiveSummaryView of(SkuArchive archive) {
        return new ArchiveSummaryView(archive.archiveNo(),
            ArchiveIdentifiers.toWire(archive.ownerId()),
            archive.fields().ownerCode(), archive.fields().name(), archive.fields().barcode(),
            archive.fields().baseUnit(), archive.fields().storageCondition(),
            archive.fields().category(), archive.fields().specification(), archive.fields().brand(),
            archive.status().name(), archive.version(), archive.dataTimestamp(),
            archive.updatedAt());
    }
}
