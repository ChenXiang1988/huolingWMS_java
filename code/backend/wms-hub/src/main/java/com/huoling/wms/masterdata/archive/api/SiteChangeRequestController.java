package com.huoling.wms.masterdata.archive.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.huoling.wms.masterdata.acceptance.application.AcceptanceCommand;
import com.huoling.wms.masterdata.acceptance.application.AcceptanceService;
import com.huoling.wms.masterdata.archive.application.ArchiveAccessGuard;
import com.huoling.wms.masterdata.archive.application.ChangeRequestService;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchivePermissionCodes;
import com.huoling.wms.masterdata.archive.domain.ChangeRequestQuery;
import com.huoling.wms.masterdata.domain.SkuChangeRequestStatus;
import com.huoling.wms.masterdata.domain.SkuChangeableField;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.identity.api.CurrentUser;
import com.huoling.wms.masterdata.identity.api.RequiresPermission;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单仓端变更申请在 Hub 侧的落点（API-011 写入权威表、API-014 查询）。
 *
 * <p><b>端内分工</b>：契约「单仓端会话与权限」规定 {@code /api/site/**} 由 Site 单元承载。
 * 本控制器是 Site 单元上行后的落点——变更申请的权威表在 Hub 库（{@code hub_product_change_request}），
 * Site 侧受理后经上行投递器调到这里，由 Hub 校验本仓数据域并写入权威表。
 * Site 单元自身也实现了一份同路径接口（本地受理 + 上行），两者不共库、不冲突。
 *
 * <p>写接口按契约返回 202：受理号由受理记录给出，申请编号在受理结果里返回。
 */
@RestController
@RequestMapping("/api/site/archive-change-requests")
public class SiteChangeRequestController {

    private static final String API_CREATE = "API-011";
    private static final String API_QUERY = "API-014";

    private final ChangeRequestService changeRequestService;
    private final ArchiveAccessGuard guard;
    private final AcceptanceService acceptance;
    private final JwtTokenService tokenService;

    public SiteChangeRequestController(ChangeRequestService changeRequestService,
                                       ArchiveAccessGuard guard,
                                       AcceptanceService acceptance,
                                       JwtTokenService tokenService) {
        this.changeRequestService = changeRequestService;
        this.guard = guard;
        this.acceptance = acceptance;
        this.tokenService = tokenService;
    }

    /** API-011：单仓发起字段变更申请，幂等作用域为本仓加接口加键。 */
    @PostMapping
    @RequiresPermission(ArchivePermissionCodes.SITE_CHANGE)
    @ApiResponses({
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_ACCEPTED,
            description = ArchiveApiDocs.ACCEPTED,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_BAD_REQUEST,
            description = ArchiveApiDocs.PARAM_INVALID + "；changeField 闭集为 name、spec、category、storageCondition",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_FORBIDDEN,
            description = ArchiveApiDocs.FORBIDDEN_SITE,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_NOT_FOUND,
            description = ArchiveApiDocs.NOT_FOUND_ARCHIVE,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_CONFLICT,
            description = "STATE_INVALID、IDEMPOTENT_IN_PROGRESS、IDEMPOTENT_KEY_CONFLICT："
                + "状态不接受变更申请、幂等记录处理中或同键异体",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_RATE_LIMITED,
            description = ArchiveApiDocs.RATE_LIMITED,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_UNAVAILABLE,
            description = ArchiveApiDocs.DEPENDENCY_UNAVAILABLE,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_INTERNAL,
            description = ArchiveApiDocs.INTERNAL_ERROR,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class)))
    })
    public ResponseEntity<ArchiveResponse<AcceptanceService.Ticket>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody CreateRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        ArchiveAccessGuard.SiteScope scope = guard.requireSite(user.accountId(), user.client());
        guard.limitSite(scope.warehouseId(), API_CREATE);
        ArchiveEntryChecks.requireId(request.archiveId(), "archiveId");
        SkuChangeableField field = ChangeFieldNames.parse(request.changeField());
        ArchiveEntryChecks.requireText(request.targetValue(), "targetValue",
            ArchiveEntryChecks.TARGET_VALUE_MAX);
        ArchiveEntryChecks.requireText(request.reason(), "reason", ArchiveEntryChecks.REASON_MAX);
        String scopeKey = AcceptanceService.SCOPE_SITE_PREFIX + scope.warehouseId();
        return ArchiveHttp.accepted(acceptance.submit(
            AcceptanceCommand.of(scopeKey, API_CREATE, idempotencyKey,
                request.archiveId(), request.toString(), SkuChangeRequestStatus.PENDING.name(),
                user.accountId()),
            () -> changeRequestService.create(request.archiveId(), scope, field,
                request.targetValue(), request.reason(), user.accountId())));
    }

    /** API-014：单仓查询本仓申请状态，含驳回原因与按档案过滤。 */
    @GetMapping
    @RequiresPermission(ArchivePermissionCodes.SITE_READ)
    public ArchiveResponse<ChangeRequestView.ChangeRequestListResponse> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String requestNo,
            @RequestParam(required = false) SkuChangeRequestStatus status,
            @RequestParam(required = false) String archiveId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        ArchiveAccessGuard.SiteScope scope = guard.requireSite(user.accountId(), user.client());
        guard.limitSite(scope.warehouseId(), API_QUERY);
        if (limit <= 0 || limit > ChangeRequestQuery.MAX_LIMIT) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + ChangeRequestQuery.MAX_LIMIT);
        }
        SkuListSort sort = SkuListSort.parse(sortBy, sortDir);
        return ArchiveResponse.ok(ChangeRequestView.ChangeRequestListResponse.of(
            changeRequestService.pageForSite(scope.warehouseId(), status, requestNo, archiveId,
                sort, cursor, limit)));
    }

    /** API-011 请求体：档案编号、变更字段（契约闭集）、目标值与原因。 */
    public record CreateRequest(String archiveId, String changeField, String targetValue,
                                String reason) {

        /** 未知字段一律拒绝：变更申请只接受契约字段闭集内的四个字段。 */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw ArchiveRequestBody.unknownField(fieldName);
        }
    }
}
