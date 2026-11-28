package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.masterdata.identity.api.CurrentUser;
import com.huoling.wms.masterdata.identity.api.RequiresPermission;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import com.huoling.wms.masterdata.owner.application.OwnerAcceptanceQueryService;
import com.huoling.wms.masterdata.owner.application.OwnerAccessGuard;
import com.huoling.wms.masterdata.owner.domain.OwnerPermissionAlias;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端货主档案受理结果查询（API-027），路径前缀 {@code /api/hub/owner-archives/acceptances}。
 *
 * <p>与商品档案的受理查询（{@code /api/hub/archive-acceptances}）刻意分成两条路径：
 * 复用同一条会让调用方必须持商品档案读权限，权限点与业务对象错配（design「受理查询接口」）。
 * 存储仍是同一张表，隔离落在 {@code api_code} 谓词与目标货主集合上
 * （{@link com.huoling.wms.masterdata.owner.application.OwnerAcceptanceQueries}）。
 *
 * <p>三态：{@code PROCESSING} 原样回出；{@code SUCCEEDED} 带结果摘要
 * （{@code ownerId}、{@code ownerNo}、{@code status}、{@code version}）；{@code FAILED} 带
 * {@code errorCode} 与 {@code errorMessage}。超过轮询上限仍处理中时返回可重试的 503，
 * 由调用方保留受理号稍后重查（契约 1.3「恢复」）。
 *
 * <p>成功响应不带 {@code dataTimestamp}：读权威库，不伪造降级标识。
 */
@RestController
@RequestMapping("/api/hub/owner-archives/acceptances")
public class HubOwnerAcceptanceController {

    private static final String API_QUERY = "API-027";

    private final OwnerAcceptanceQueryService acceptanceQueries;
    private final OwnerAccessGuard guard;
    private final JwtTokenService tokenService;

    public HubOwnerAcceptanceController(OwnerAcceptanceQueryService acceptanceQueries,
                                        OwnerAccessGuard guard,
                                        JwtTokenService tokenService) {
        this.acceptanceQueries = acceptanceQueries;
        this.guard = guard;
        this.tokenService = tokenService;
    }

    /** API-027：按受理号查询处理中、成功或失败三态。 */
    @GetMapping("/{acceptNo}")
    @RequiresPermission(OwnerPermissionAlias.READ)
    public OwnerResponse<OwnerAcceptanceQueryService.OwnerAcceptanceView> detail(
            @Parameter(description = "受理号", schema = @Schema(type = "string"))
            @PathVariable String acceptNo,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        guard.limitHub(API_QUERY);
        OwnerScope scope = guard.requireHubScope(user.accountId(), user.client());
        return OwnerResponse.ok(acceptanceQueries.find(acceptNo, scope));
    }
}
