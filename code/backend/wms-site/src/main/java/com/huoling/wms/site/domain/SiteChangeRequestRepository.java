package com.huoling.wms.site.domain;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 单仓变更申请仓储：读方法一律带 {@code siteId}，不提供跨仓重载。 */
public interface SiteChangeRequestRepository {

    /** 本仓某档案是否已有待审核申请（同一档案同时只允许一条）。 */
    boolean hasPending(Long siteId, String archiveId);

    void insert(SiteChangeRequest request);

    /** 本仓分页查询，键集分页锚在主键上。 */
    List<SiteChangeRequest> page(SiteChangeRequestQuery query);

    /** 待上行的申请：{@code PENDING} 或 {@code FAILED} 且到了重试时间。 */
    List<SiteChangeRequest> findUploadable(int limit, Instant now);

    void markUploaded(String requestNo, Instant now);

    void markUploadFailed(String requestNo, String error, Instant now);

    /**
     * 应用审核结果（下行事件）。
     *
     * <p>按 {@code lastEventId} 去重：同一条事件重复投递时不再改一次状态。
     */
    void applyReviewResult(String requestNo, String status, String reviewReason,
                           Long archiveReviewedVersion, String eventId, Instant now);

    /**
     * 本仓查询条件：本仓必填，其余可空。
     *
     * <p>排序字段按申请字段解释（契约字段结构表：变更申请列表按申请创建时间、所涉档案字段排序）：
     * {@code updateTime} 落申请创建时间，{@code ownerCode} 落申请归属货主，{@code name} 落申请的变更字段，
     * 不拿本仓缓存里的档案列来排。游标锚在同一列上，换排序键后旧游标失效。
     */
    record SiteChangeRequestQuery(Long siteId, String status, String requestNo, String archiveId,
                                  SkuListSort sort, SitePageCursor cursor, int limit) {

        public static final int MAX_LIMIT = 100;

        public SiteChangeRequestQuery {
            if (siteId == null) {
                throw new SiteException(ArchiveContractCodes.FORBIDDEN_SITE, "缺少本仓数据域，拒绝查询");
            }
            if (limit <= 0 || limit > MAX_LIMIT + 1) {
                throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                    "参数 limit 取值 1 到 " + MAX_LIMIT);
            }
            if (sort == null) {
                // 缺省与契约一致：按申请创建时间降序。
                sort = SkuListSort.parse(null, null);
            }
        }
    }

    /** 便于调用方判空：{@code Optional} 版本的本仓查询。 */
    Optional<SiteChangeRequest> findByRequestNo(Long siteId, String requestNo);
}
