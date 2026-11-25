package com.huoling.wms.site.application;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuSnapshotRule;
import com.huoling.wms.site.domain.SiteException;
import com.huoling.wms.site.domain.SitePageCursor;
import com.huoling.wms.site.domain.SkuSnapshot;
import com.huoling.wms.site.domain.SkuSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 本仓生效档案查询（API-010）。
 *
 * <p>只读缓存，数据时间戳随每个条目返回（契约 API-010 响应），并给出待刷新标记：
 * 超过新鲜度阈值即标 {@code stale}，界面据此提示「待刷新」，不把缓存伪装成最新结果。
 *
 * <p>排序字段与方向来自契约 {@code sortBy}、{@code sortDir}，由接口层解析后传进来；
 * 游标锚在同一排序字段上，换排序键后旧游标被拒绝，不按另一种顺序续翻。
 *
 * <p>阈值取五分钟是实现的默认值：design 只要求「缓存版本落后时标记为待刷新」，
 * 未给具体阈值（已登记为待确认项）；阈值只影响展示提示，不参与任何状态判断。
 */
@Service
public class SkuSnapshotQueryService {

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final SkuSnapshotRepository snapshotRepository;

    public SkuSnapshotQueryService(SkuSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    /** API-010：本仓筛选、更新时间范围、排序与键集分页。 */
    public SnapshotPage page(Long siteId, String ownerCode, String name, String category,
                             Instant updatedFrom, Instant updatedTo, SkuListSort sort, String cursor,
                             int limit) {
        if (limit <= 0 || limit > SkuSnapshotRepository.SnapshotQuery.MAX_LIMIT) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + SkuSnapshotRepository.SnapshotQuery.MAX_LIMIT);
        }
        List<SkuSnapshot> rows = snapshotRepository.page(new SkuSnapshotRepository.SnapshotQuery(
            siteId, blankToNull(ownerCode), blankToNull(name), blankToNull(category), updatedFrom,
            updatedTo, sort, SitePageCursor.decode(cursor, sort), limit + 1));
        boolean hasMore = rows.size() > limit;
        List<SkuSnapshot> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !items.isEmpty()
            ? cursorOf(items.get(items.size() - 1), sort).encode()
            : null;
        Instant now = Instant.now();
        Instant dataTimestamp = items.stream()
            .map(SkuSnapshot::dataTimestamp)
            .filter(java.util.Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(now);
        return new SnapshotPage(items.stream().map(item -> SnapshotView.of(item, now)).toList(),
            nextCursor, hasMore, dataTimestamp);
    }

    /** 下一页锚点：取本页最后一条在当前排序字段上的值，与仓储层的 ORDER BY 同一列。 */
    private SitePageCursor cursorOf(SkuSnapshot snapshot, SkuListSort sort) {
        return switch (sort.key()) {
            case OWNER_CODE -> SitePageCursor.ofText(sort, snapshot.ownerCode(), snapshot.id());
            case NAME -> SitePageCursor.ofText(sort, snapshot.name(), snapshot.id());
            case UPDATE_TIME -> SitePageCursor.ofTime(sort, snapshot.dataTimestamp(), snapshot.id());
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 条目视图：契约字段名（{@code archiveId}、{@code spec}）加版本号与数据时间戳。 */
    public record SnapshotView(String archiveId, String ownerId, String ownerCode, String name,
                               String barcode, String baseUnit, String storageCondition,
                               String category, String spec, String status, long version,
                               Instant dataTimestamp, boolean stale) {

        static SnapshotView of(SkuSnapshot snapshot, Instant now) {
            return new SnapshotView(snapshot.archiveNo(),
                snapshot.ownerId() == null ? null : String.valueOf(snapshot.ownerId()),
                snapshot.ownerCode(), snapshot.name(), snapshot.barcode(), snapshot.baseUnit(),
                snapshot.storageCondition(), snapshot.category(), snapshot.specification(),
                snapshot.status(), snapshot.version(), snapshot.dataTimestamp(),
                SkuSnapshotRule.stale(snapshot.dataTimestamp(), now, STALE_AFTER));
        }
    }

    /** 列表结果：条目、下一页游标、是否还有数据与数据时间戳。 */
    public record SnapshotPage(List<SnapshotView> items, String nextCursor, boolean hasMore,
                               Instant dataTimestamp) {
    }
}
