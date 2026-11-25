package com.huoling.wms.site.api;

import com.huoling.wms.common.archive.ArchivePermissionCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.site.application.SiteRateLimiter;
import com.huoling.wms.site.application.SkuSnapshotQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单仓端本仓档案查询（API-010），路径前缀 {@code /api/site/archives}。
 *
 * <p>数据域取令牌里的唯一 {@code siteId}，请求参数里不出现仓标识，也不接受覆盖。
 * 只读本仓缓存，权威源在 Hub；响应必带数据时间戳，缓存落后时由 {@code stale} 标记提示待刷新。
 */
@RestController
@RequestMapping("/api/site/archives")
public class SiteArchiveController {

    private static final String API_QUERY = "API-010";

    private final SkuSnapshotQueryService queryService;
    private final SiteRateLimiter rateLimiter;

    public SiteArchiveController(SkuSnapshotQueryService queryService, SiteRateLimiter rateLimiter) {
        this.queryService = queryService;
        this.rateLimiter = rateLimiter;
    }

    /** API-010：本仓筛选、更新时间范围、排序与键集分页；排序字段与方向取契约闭集。 */
    @GetMapping
    public SiteApiResponse<SkuSnapshotQueryService.SnapshotPage> list(
            HttpServletRequest request,
            @RequestParam(required = false) String ownerCode,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) java.time.Instant updatedFrom,
            @RequestParam(required = false) java.time.Instant updatedTo,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        SiteSession session = SiteSessions.require(request, ArchivePermissionCodes.SITE_READ);
        rateLimiter.check(session.siteId(), API_QUERY);
        SkuListSort sort = SkuListSort.parse(sortBy, sortDir);
        return SiteApiResponse.ok(queryService.page(session.siteId(), ownerCode, name, category,
            updatedFrom, updatedTo, sort, cursor, limit));
    }
}
