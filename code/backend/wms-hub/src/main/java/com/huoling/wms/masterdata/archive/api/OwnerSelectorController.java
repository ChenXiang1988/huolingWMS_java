package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.application.ArchiveAccessGuard;
import com.huoling.wms.masterdata.archive.application.OwnerSelectorService;
import com.huoling.wms.masterdata.archive.domain.ArchivePermissionCodes;
import com.huoling.wms.masterdata.identity.api.CurrentUser;
import com.huoling.wms.masterdata.identity.api.RequiresPermission;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端货主选择器（API-020），路径前缀 {@code /api/hub/owners}。
 *
 * <p>权限点与代建同一枚 {@code archive.hub.create}：选择器的唯一用途是给代建指定归属，
 * 没有代建权的账号看不到货主清单。数据域为全量但只读，不改变任何归属。
 *
 * <p>响应字段照抄契约：条目为 {@code ownerId} 与 {@code ownerName}，
 * {@code dataTimestamp} 只在查询降级时出现，正常查询不带。
 */
@RestController
@RequestMapping("/api/hub/owners")
public class OwnerSelectorController {

    private static final String API_QUERY = "API-020";

    private final OwnerSelectorService ownerSelectorService;
    private final ArchiveAccessGuard guard;
    private final JwtTokenService tokenService;

    public OwnerSelectorController(OwnerSelectorService ownerSelectorService,
                                   ArchiveAccessGuard guard,
                                   JwtTokenService tokenService) {
        this.ownerSelectorService = ownerSelectorService;
        this.guard = guard;
        this.tokenService = tokenService;
    }

    /**
     * API-020：按货主标识、展示名搜索归属货主，键集分页。
     *
     * <p>{@code ownerCode} 精确匹配货主标识，{@code name} 模糊匹配展示名，两者同时给出时取交集。
     */
    @GetMapping
    @RequiresPermission(ArchivePermissionCodes.HUB_CREATE)
    public ArchiveResponse<OwnerSelectorView.OwnerSelectorPage> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Parameter(description = "货主标识，精确匹配", schema = @Schema(type = "string"))
            @RequestParam(required = false) String ownerCode,
            @Parameter(description = "货主展示名，模糊匹配", schema = @Schema(type = "string"))
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        CurrentUser.from(tokenService, authorization);
        guard.limitHub(API_QUERY);
        return ArchiveResponse.ok(OwnerSelectorView.OwnerSelectorPage.of(
            ownerSelectorService.page(ownerCode, name, cursor, limit)));
    }
}
