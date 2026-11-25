package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.masterdata.domain.SkuChangeRequestStatus;
import com.huoling.wms.masterdata.domain.SkuListSort;

/**
 * 变更申请列表条件。
 *
 * <p>单仓端固定带 {@code siteId}（本仓域），运营端可空表示全量；
 * 仓储层把非空条件拼进 SQL，业务代码不写隔离条件（INV-06）。
 *
 * <p>{@code archiveId} 对应契约里的按档案过滤（API-012、API-014），是档案的系统编号而不是库内主键。
 *
 * <p>{@code sort} 取契约 {@code sortBy}、{@code sortDir}，但落列按申请字段解释（契约字段结构表：
 * 变更申请列表按申请创建时间、所涉档案字段排序），不拿档案表的列来排：
 * {@code updateTime} 落申请创建时间，{@code ownerCode} 落申请归属货主，
 * {@code name} 落申请的变更字段（即所涉档案的字段名）。
 */
public record ChangeRequestQuery(
        Long ownerId,
        Long siteId,
        SkuChangeRequestStatus status,
        String requestNo,
        String archiveId,
        SkuListSort sort,
        SkuPageCursor cursor,
        int limit) {

    public static final int MAX_LIMIT = 100;

    public ChangeRequestQuery {
        // 外部入参上限一百由接口层校验；这里多放一条，供查询服务「多取一条判定是否有下一页」。
        if (limit <= 0 || limit > MAX_LIMIT + 1) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "参数 limit 取值 1 到 " + MAX_LIMIT);
        }
        if (sort == null) {
            // 缺省与契约一致：按申请创建时间降序，与列表默认视图相同。
            sort = SkuListSort.parse(null, null);
        }
    }

    /** 单仓域查询：本仓必填，缺失即越权拒绝，不回落成全量。 */
    public static ChangeRequestQuery forSite(Long siteId, SkuChangeRequestStatus status,
                                             String requestNo, String archiveId, SkuListSort sort,
                                             SkuPageCursor cursor, int limit) {
        if (siteId == null) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE, "缺少本仓数据域，拒绝查询");
        }
        return new ChangeRequestQuery(null, siteId, status, requestNo, archiveId, sort, cursor, limit);
    }
}
