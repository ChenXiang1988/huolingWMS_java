package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import com.huoling.wms.masterdata.domain.SkuListSort;

import java.time.Instant;

/**
 * 档案列表查询条件。
 *
 * <p>{@code ownerId} 非空即货主域：仓储层强制把它拼进条件（INV-06），业务代码不拿它做可选分支；
 * 运营端全量视图传 {@code null}，由功能权限 {@code archive.hub.read} 把关。
 *
 * <p>{@code sort} 取契约 {@code sortBy}、{@code sortDir}（API-005、API-007），游标锚点按它取列，
 * 排序字段一变，旧游标即失效（{@link SkuPageCursor} 编码里带着字段与方向）。
 */
public record SkuArchiveQuery(
        Long ownerId,
        String ownerCode,
        String name,
        SkuArchiveStatus status,
        String category,
        Instant updatedFrom,
        Instant updatedTo,
        SkuListSort sort,
        SkuPageCursor cursor,
        int limit) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public SkuArchiveQuery {
        // 外部入参上限一百由接口层校验；这里多放一条，供查询服务「多取一条判定是否有下一页」。
        if (limit <= 0 || limit > MAX_LIMIT + 1) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "参数 limit 取值 1 到 " + MAX_LIMIT);
        }
        if (updatedFrom != null && updatedTo != null && updatedFrom.isAfter(updatedTo)) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "参数 updatedFrom 不得晚于 updatedTo");
        }
        if (sort == null) {
            // 缺省与契约一致：按更新时间降序；游标解码已按请求的 sort 校验过，这里不再兜底其他取值。
            sort = SkuListSort.parse(null, null);
        }
    }

    /** 货主域查询：ownerId 必填，仓储层据此注入条件。 */
    public static SkuArchiveQuery forOwner(Long ownerId, String ownerCode, String name,
                                           SkuArchiveStatus status, String category,
                                           Instant updatedFrom, Instant updatedTo,
                                           SkuListSort sort, SkuPageCursor cursor, int limit) {
        if (ownerId == null) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "缺少货主数据域，拒绝查询");
        }
        return new SkuArchiveQuery(ownerId, ownerCode, name, status, category, updatedFrom, updatedTo,
            sort, cursor, limit);
    }

    /** 全量视图：运营端专用，归属不过滤。 */
    public static SkuArchiveQuery allOwners(Long ownerIdFilter, String ownerCode, String name,
                                            SkuArchiveStatus status, String category,
                                            Instant updatedFrom, Instant updatedTo,
                                            SkuListSort sort, SkuPageCursor cursor, int limit) {
        return new SkuArchiveQuery(ownerIdFilter, ownerCode, name, status, category, updatedFrom,
            updatedTo, sort, cursor, limit);
    }
}
