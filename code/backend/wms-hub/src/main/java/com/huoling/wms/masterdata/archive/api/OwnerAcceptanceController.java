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
 * 货主端受理结果查询（API-015），路径前缀 {@code /api/owner/archive-acceptances}。
 *
 * <p>数据域注入归属货主：作用域键与写接口受理时写入的一致，跨货主查询按 {@code NOT_FOUND_ARCHIVE} 拒绝，
 * 不泄露他人受理单是否存在（契约「鉴权落点」）。
 */
@RestController
@RequestMapping("/api/owner/archive-acceptances")
public class OwnerAcceptanceController {

    private static final String API_QUERY = "API-015";

    private final ArchiveAcceptanceService acceptanceService;
    private final ArchiveAccessGuard guard;
    private final JwtTokenService tokenService;

    public OwnerAcceptanceController(ArchiveAcceptanceService acceptanceService,
                                     ArchiveAccessGuard guard,
                                     JwtTokenService tokenService) {
        this.acceptanceService = acceptanceService;
        this.guard = guard;
        this.tokenService = tokenService;
    }

    /** API-015：按受理号查询处理中、成功或失败结果。 */
    @GetMapping("/{acceptNo}")
    @RequiresPermission(ArchivePermissionCodes.OWNER_READ)
    public ArchiveResponse<ArchiveAcceptanceService.View> detail(
            @PathVariable String acceptNo,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        Long ownerId = guard.requireOwner(user.accountId(), user.client());
        guard.limitOwner(ownerId, API_QUERY);
        return ArchiveResponse.ok(acceptanceService.find(String.valueOf(ownerId), acceptNo));
    }
}
