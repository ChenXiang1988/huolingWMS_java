package com.huoling.wms.site.domain;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 本仓缓存仓储。
 *
 * <p>读方法一律带 {@code siteId}（本仓域），实现里拼进 SQL；没有「不带仓条件」的重载，
 * 避免调用方漏注入数据域（INV-06、INV-19）。
 */
public interface SkuSnapshotRepository {

    /** 本仓按系统编号取；不存在或不属于本仓返回空。 */
    Optional<SkuSnapshot> find(Long siteId, String archiveNo);

    /** 本仓列表，键集分页。 */
    List<SkuSnapshot> page(SnapshotQuery query);

    /**
     * 应用一条下行摘要。
     *
     * <p>两层保护：调用方先用 kernel 的 {@code SkuSnapshotRule} 判定版本，
     * 落库时再用「版本更大才覆盖」的条件更新兜住并发重复投递。
     */
    void apply(SkuSnapshot snapshot);

    /**
     * 单仓列表条件：本仓必填，其余为可空筛选；时间范围与 {@code updateTime} 排序都按数据时间戳
     * （契约 API-010：更新时间范围过滤、响应带数据时间戳）。
     *
     * <p>排序字段取契约 {@code sortBy}：{@code updateTime} 落数据时间戳，{@code ownerCode} 落货主编码，
     * {@code name} 落名称；游标锚在同一列上，换排序键后旧游标失效。
     */
    record SnapshotQuery(Long siteId, String ownerCode, String name, String category,
                         Instant updatedFrom, Instant updatedTo, SkuListSort sort,
                         SitePageCursor cursor, int limit) {

        public static final int MAX_LIMIT = 100;

        public SnapshotQuery {
            if (siteId == null) {
                throw new SiteException(ArchiveContractCodes.FORBIDDEN_SITE, "缺少本仓数据域，拒绝查询");
            }
            if (limit <= 0 || limit > MAX_LIMIT + 1) {
                throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                    "参数 limit 取值 1 到 " + MAX_LIMIT);
            }
            if (sort == null) {
                // 缺省与契约一致：按更新时间降序。
                sort = SkuListSort.parse(null, null);
            }
        }
    }
}
