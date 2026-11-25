package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.application.ArchiveAcceptanceService;
import com.huoling.wms.masterdata.archive.application.ArchiveAccessGuard;
import com.huoling.wms.masterdata.archive.domain.ArchivePermissionCodes;
import com.huoling.wms.masterdata.identity.api.CurrentUser;
import com.huoling.wms.masterdata.identity.api.RequiresPermission;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端受理结果查询（API-016），路径前缀 {@code /api/hub/archive-acceptances}。
 *
 * <p>数据域为全量：运营可查任意端的受理单，用于批量操作的逐条结果与排障；
 * 受理响应同样只回结果摘要，不回最终业务状态之外的内部实体。
 */
@RestController
@RequestMapping("/api/hub/archive-acceptances")
public class HubAcceptanceController {

    private static final String API_QUERY = "API-016";

    private final ArchiveAcceptanceService acceptanceService;
    private final ArchiveAccessGuard guard;
    private final JwtTokenService tokenService;

    public HubAcceptanceController(ArchiveAcceptanceService acceptanceService,
                                   ArchiveAccessGuard guard,
                                   JwtTokenService tokenService) {
        this.acceptanceService = acceptanceService;
        this.guard = guard;
        this.tokenService = tokenService;
    }

    /** API-016：按受理号查询，可选作用域为空表示全量。 */
    @GetMapping("/{acceptNo}")
    @RequiresPermission(ArchivePermissionCodes.HUB_READ)
    public ArchiveResponse<ArchiveAcceptanceService.View> detail(
            @PathVariable String acceptNo,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CurrentUser.from(tokenService, authorization);
        guard.limitHub(API_QUERY);
        return ArchiveResponse.ok(acceptanceService.find(null, acceptNo));
    }
}
