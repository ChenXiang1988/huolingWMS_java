package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveQuery;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.archive.domain.SkuPageCursor;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import com.huoling.wms.masterdata.domain.SkuListSort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 商品档案查询服务（API-005 货主自有、API-007 运营全量）。
 *
 * <p>列表只用键集分页：多取一条判定是否还有下一页，游标锚在最后一条的（排序键值, 主键）上。
 * 排序字段与方向来自契约 {@code sortBy}、{@code sortDir}，由接口层解析后传进来；
 * 游标编码里带着同一组字段与方向，因此换排序键后旧游标会被拒绝，不会按另一种顺序续翻。
 *
 * <p>降级查询必须带数据时间戳，因此响应统一回出 {@code dataTimestamp}。
 */
@Service
public class SkuArchiveQueryService {

    private final SkuArchiveRepository archiveRepository;

    public SkuArchiveQueryService(SkuArchiveRepository archiveRepository) {
        this.archiveRepository = archiveRepository;
    }

    /** API-005：货主自有档案，归属由 {@code ownerId} 强制注入。 */
    public ArchivePage pageForOwner(Long ownerId, String ownerCode, String name, SkuArchiveStatus status,
                                    String category, Instant updatedFrom, Instant updatedTo,
                                    SkuListSort sort, String cursor, int limit) {
        SkuArchiveQuery query = SkuArchiveQuery.forOwner(ownerId, ownerCode, name, status, category,
            updatedFrom, updatedTo, sort, SkuPageCursor.decode(cursor, sort), limit + 1);
        return page(query, limit);
    }

    /** API-007：运营全量视图，可按归属货主过滤但不改变归属。 */
    public ArchivePage pageForHub(Long ownerIdFilter, String ownerCode, String name,
                                  SkuArchiveStatus status, String category, Instant updatedFrom,
                                  Instant updatedTo, SkuListSort sort, String cursor, int limit) {
        SkuArchiveQuery query = SkuArchiveQuery.allOwners(ownerIdFilter, ownerCode, name, status,
            category, updatedFrom, updatedTo, sort, SkuPageCursor.decode(cursor, sort), limit + 1);
        return page(query, limit);
    }

    private ArchivePage page(SkuArchiveQuery query, int limit) {
        List<SkuArchive> rows = archiveRepository.page(query);
        boolean hasMore = rows.size() > limit;
        List<SkuArchive> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !items.isEmpty()
            ? cursorOf(items.get(items.size() - 1), query.sort()).encode()
            : null;
        Instant dataTimestamp = items.isEmpty() ? Instant.now() : items.get(0).dataTimestamp();
        return new ArchivePage(items, nextCursor, hasMore, dataTimestamp);
    }

    /** 下一页锚点：取本页最后一条在当前排序字段上的值，与仓储层的 ORDER BY 同一列。 */
    private SkuPageCursor cursorOf(SkuArchive archive, SkuListSort sort) {
        return switch (sort.key()) {
            case OWNER_CODE -> SkuPageCursor.ofText(sort, archive.fields().ownerCode(), archive.id());
            case NAME -> SkuPageCursor.ofText(sort, archive.fields().name(), archive.id());
            case UPDATE_TIME -> SkuPageCursor.ofTime(sort, archive.updatedAt(), archive.id());
        };
    }

    /** 列表结果：条目、下一页游标、是否还有数据与数据时间戳。 */
    public record ArchivePage(List<SkuArchive> items, String nextCursor, boolean hasMore,
                              Instant dataTimestamp) {
    }
}
