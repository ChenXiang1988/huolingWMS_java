package com.huoling.wms.site.api;

import com.huoling.wms.common.archive.ArchivePermissionCodes;
import com.huoling.wms.site.application.SiteAcceptanceService;
import com.huoling.wms.site.application.SiteRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单仓端受理结果查询（API-017），路径前缀 {@code /api/site/archive-acceptances}。
 *
 * <p>数据域注入令牌里的唯一 {@code siteId}：跨仓查询受理号按 {@code NOT_FOUND_ARCHIVE} 拒绝，
 * 不泄露其他仓受理单是否存在。处理中不伪造结果，成功返回结果摘要，失败返回契约错误码。
 */
@RestController
@RequestMapping("/api/site/archive-acceptances")
public class SiteAcceptanceController {

    private static final String API_QUERY = "API-017";

    private final SiteAcceptanceService acceptanceService;
    private final SiteRateLimiter rateLimiter;

    public SiteAcceptanceController(SiteAcceptanceService acceptanceService,
                                    SiteRateLimiter rateLimiter) {
        this.acceptanceService = acceptanceService;
        this.rateLimiter = rateLimiter;
    }

    /** API-017：按受理号查询处理中、成功或失败结果。 */
    @GetMapping("/{acceptNo}")
    public SiteApiResponse<SiteAcceptanceService.View> detail(HttpServletRequest request,
                                                              @PathVariable String acceptNo) {
        SiteSession session = SiteSessions.require(request, ArchivePermissionCodes.SITE_READ);
        rateLimiter.check(session.siteId(), API_QUERY);
        return SiteApiResponse.ok(acceptanceService.find(session.siteId(), acceptNo));
    }
}
