package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OwnerDirectoryMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OwnerDirectoryPO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 货主选择器（API-020）：运营代建时搜索并选择归属货主。
 *
 * <p>数据域为全量，但只读：选择器不创建货主，也不改变任何档案归属（specs product-archive-access）。
 * 列表只用键集分页，游标锚在货主标识上，与档案列表的（更新时间, 主键）游标互不共用。
 *
 * <p>筛选按契约 API-020：{@code ownerCode} 按货主标识精确匹配，{@code name} 按展示名模糊匹配，
 * 两者同时给出时取交集；条件透传给投影查询，由同一条语句一次判定。
 *
 * <p>数据时间戳只在降级时才有值：正常查询读实时目录，{@code dataTimestamp} 为 {@code null}
 * 且不出现在响应里；降级来源（快照类数据）落地后由该字段带出来源时间，不把实时结果伪装成降级数据。
 */
@Service
public class OwnerSelectorService {

    /** 单页上限与档案列表一致（契约「分页约定」）。 */
    public static final int MAX_LIMIT = 100;

    private final OwnerDirectoryMapper mapper;

    public OwnerSelectorService(OwnerDirectoryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按货主标识、展示名分页查询。
     *
     * @param cursor 上一页最后一个货主标识，空表示第一页
     */
    public OwnerPage page(String ownerCode, String name, String cursor, int limit) {
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + MAX_LIMIT);
        }
        Long cursorId = parseCursor(cursor);
        List<OwnerDirectoryPO> rows = mapper.page(blankToNull(ownerCode), blankToNull(name),
            cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<OwnerDirectoryPO> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !items.isEmpty()
            ? String.valueOf(items.get(items.size() - 1).getOwnerId())
            : null;
        List<OwnerOption> options = items.stream()
            .map(row -> new OwnerOption(String.valueOf(row.getOwnerId()), row.getOwnerName()))
            .toList();
        return new OwnerPage(options, nextCursor, hasMore, null);
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(cursor);
        } catch (NumberFormatException e) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 cursor 取值不合法：收到 " + cursor);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 选择器条目：契约响应字段 {@code ownerId} 与 {@code ownerName}，标识一律十进制字符串。 */
    public record OwnerOption(String ownerId, String ownerName) {
    }

    /** 选择器分页结果：条目、下一页游标、是否还有下一页与降级数据时间戳（正常查询为空）。 */
    public record OwnerPage(List<OwnerOption> items, String nextCursor, boolean hasMore,
                            Instant dataTimestamp) {
    }
}
