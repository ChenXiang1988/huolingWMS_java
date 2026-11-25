package com.huoling.wms.masterdata.archive.domain;

import java.time.Instant;
import java.util.List;

/**
 * 下行摘要载荷：档案编号、版本号、数据时间戳与变更字段摘要，加上单仓缓存要展示的字段。
 *
 * <p>为什么不止四个字段：单仓的 {@code site_sku_snapshot} 要按本表口径展示「本仓生效档案摘要」，
 * 只给档案编号与版本号无法落库；载荷仍不含内部实体（不带审核留痕、操作人与库位关联），
 * 只带档案上可公开的展示字段。字段口径与契约「字段结构与枚举」一致（{@code spec} 为规格型号）。
 *
 * <p>单仓按 {@code eventId}（档案编号加版本号）去重，版本落后即丢弃（{@link SkuSnapshotRule}）。
 */
public record ArchiveEventPayload(
        String archiveId,
        String ownerId,
        String ownerCode,
        String name,
        String barcode,
        String baseUnit,
        String storageCondition,
        String category,
        String spec,
        String status,
        long version,
        Instant dataTimestamp,
        List<String> changedFields) {

    /** 按档案当前状态生成摘要；{@code changedFields} 只列本次变更的字段名。 */
    public static ArchiveEventPayload of(SkuArchive archive, List<String> changedFields) {
        return new ArchiveEventPayload(archive.archiveNo(),
            archive.ownerId() == null ? null : String.valueOf(archive.ownerId()),
            archive.fields().ownerCode(), archive.fields().name(), archive.fields().barcode(),
            archive.fields().baseUnit(), archive.fields().storageCondition(),
            archive.fields().category(), archive.fields().specification(), archive.status().name(),
            archive.version(), archive.dataTimestamp(), changedFields);
    }
}
