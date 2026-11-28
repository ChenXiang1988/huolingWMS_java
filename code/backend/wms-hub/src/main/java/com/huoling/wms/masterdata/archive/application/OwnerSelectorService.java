package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.OwnerSelectorCursor;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OwnerDirectoryMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OwnerDirectoryPO;
import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerListSortKey;
import com.huoling.wms.masterdata.identity.domain.DataScope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 货主选择器（API-020，tasks 1.12）：运营端在代建商品档案与开通货主端账号时检索货主。
 *
 * <p>数据来源已换成权威主数据 {@code wms_owner}，只返回可用货主：不再从货主端账号反向派生
 * （spec「货主选择器只暴露可用货主」），断掉账号数据也照样能查到货主。
 *
 * <p>检索口径按契约 API-020：{@code ownerNo} 精确、{@code ownerName} 模糊，两者同时给出取交集；
 * 排序白名单 {@code ownerNo}、{@code ownerName}，缺省按编号升序；键集分页，游标锚在
 * （排序键值, 货主编号）上。
 *
 * <p>数据域：域为「全部」返回全量，域为「指定列表」只返回域内货主；域为空在入口就被
 * {@link ArchiveAccessGuard#requireHubOwnerScope} 拒绝，不会走到这里。
 *
 * <p>成功响应不带 {@code dataTimestamp}：读权威库、无副本，不伪造降级标识（契约 1.1）。
 */
@Service
public class OwnerSelectorService {

    /** 单页上限与档案列表一致（契约 1.1「分页」）。 */
    public static final int MAX_LIMIT = 100;

    private final OwnerDirectoryMapper mapper;

    public OwnerSelectorService(OwnerDirectoryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按货主编号、货主名称分页查询。
     *
     * @param cursor 上一页最后一条的锚点，空表示第一页
     */
    public OwnerPage page(DataScope scope, String ownerNo, String ownerName, OwnerListSort sort,
                          String cursor, int limit) {
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + MAX_LIMIT);
        }
        if (scope == null) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "缺少货主数据域，拒绝访问");
        }
        if (!scope.allOwners() && scope.ownerIds().isEmpty()) {
            // 指定列表为空即空域：返回空列表都不算，直接拒绝，绝不回落成全量（INV-06）。
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "货主数据域为空，拒绝访问");
        }
        OwnerListSort effective = sort == null ? OwnerListSort.parse(null, null) : sort;
        OwnerSelectorCursor anchor = OwnerSelectorCursor.decode(cursor, effective);

        List<OwnerDirectoryPO> rows = mapper.page(blankToNull(ownerNo), blankToNull(ownerName),
            scope.allOwners(), scope.ownerIds(), effective.keyName(), effective.ascending(),
            anchor == null ? null : anchor.value(),
            anchor == null ? null : anchor.ownerNo(), limit + 1);

        boolean hasMore = rows.size() > limit;
        List<OwnerDirectoryPO> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !items.isEmpty()
            ? OwnerSelectorCursor.of(effective, sortValueOf(items.get(items.size() - 1), effective),
                items.get(items.size() - 1).getOwnerNo()).encode()
            : null;
        List<OwnerOption> options = items.stream()
            .map(row -> new OwnerOption(String.valueOf(row.getOwnerId()), row.getOwnerNo(),
                row.getOwnerName()))
            .toList();
        return new OwnerPage(options, nextCursor, hasMore);
    }

    /** 本页最后一条在当前排序键上的取值，与 SQL 的 ORDER BY 同一列。 */
    private String sortValueOf(OwnerDirectoryPO row, OwnerListSort sort) {
        return sort.key() == OwnerListSortKey.OWNER_NAME ? row.getOwnerName() : row.getOwnerNo();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 选择器条目：契约响应字段 {@code ownerId}、{@code ownerNo}、{@code ownerName}。 */
    public record OwnerOption(String ownerId, String ownerNo, String ownerName) {
    }

    /** 选择器分页结果：条目、下一页游标与是否还有下一页。 */
    public record OwnerPage(List<OwnerOption> items, String nextCursor, boolean hasMore) {
    }
}
