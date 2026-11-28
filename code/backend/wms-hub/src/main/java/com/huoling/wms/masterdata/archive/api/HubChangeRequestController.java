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
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuChangeRequestStatus;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.identity.api.CurrentUser;
import com.huoling.wms.masterdata.identity.api.RequiresPermission;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端变更申请接口（API-012 查询、API-013 审核），路径前缀 {@code /api/hub/archive-change-requests}。
 *
 * <p>数据域为全量；审核通过即写回权威档案、递增版本号并产生下行事件，与申请状态一起在同一本地事务内完成。
 * 审核接口按契约返回 202 受理，执行结果由 API-016 查询。
 */
@RestController
@RequestMapping("/api/hub/archive-change-requests")
public class HubChangeRequestController {

    private static final String API_QUERY = "API-012";
    private static final String API_REVIEW = "API-013";
    private static final String HUB_SCOPE = AcceptanceService.SCOPE_HUB;

    private final ChangeRequestService changeRequestService;
    private final ArchiveAccessGuard guard;
    private final AcceptanceService acceptance;
    private final JwtTokenService tokenService;

    public HubChangeRequestController(ChangeRequestService changeRequestService,
                                      ArchiveAccessGuard guard,
                                      AcceptanceService acceptance,
                                      JwtTokenService tokenService) {
        this.changeRequestService = changeRequestService;
        this.guard = guard;
        this.acceptance = acceptance;
        this.tokenService = tokenService;
    }

    /** API-012：查询变更申请，可按归属货主、本仓与档案过滤。 */
    @GetMapping
    @RequiresPermission(ArchivePermissionCodes.HUB_READ)
    public ArchiveResponse<ChangeRequestView.ChangeRequestListResponse> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) SkuChangeRequestStatus status,
            @RequestParam(required = false) String requestNo,
            @RequestParam(required = false) String archiveId,
            @Parameter(description = "排序字段：updateTime、ownerCode、name",
                schema = @Schema(type = "string")) @RequestParam(required = false) String sortBy,
            @Parameter(description = "排序方向：asc、desc，缺省 desc",
                schema = @Schema(type = "string")) @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        CurrentUser.from(tokenService, authorization);
        guard.limitHub(API_QUERY);
        if (limit <= 0 || limit > ChangeRequestQuery.MAX_LIMIT) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + ChangeRequestQuery.MAX_LIMIT);
        }
        Long ownerFilter = ArchiveIdentifiers.parseOrNull(ownerId, "ownerId");
        Long siteFilter = ArchiveIdentifiers.parseOrNull(siteId, "siteId");
        SkuListSort sort = SkuListSort.parse(sortBy, sortDir);
        return ArchiveResponse.ok(ChangeRequestView.ChangeRequestListResponse.of(
            changeRequestService.pageForHub(ownerFilter, siteFilter, status, requestNo, archiveId,
                sort, cursor, limit)));
    }

    /** API-013：审核变更申请，通过即写回权威档案并下行，响应只回受理号与受理时状态快照。 */
    @PostMapping("/{id}/review")
    @RequiresPermission(ArchivePermissionCodes.HUB_REVIEW)
    @ApiResponses({
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_ACCEPTED,
            description = ArchiveApiDocs.ACCEPTED,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_BAD_REQUEST,
            description = ArchiveApiDocs.PARAM_INVALID,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_NOT_FOUND,
            description = ArchiveApiDocs.NOT_FOUND_ARCHIVE,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_CONFLICT,
            description = ArchiveApiDocs.CONFLICT,
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
    public ResponseEntity<ArchiveResponse<AcceptanceService.Ticket>> review(
            @Parameter(description = "申请编号", schema = @Schema(type = "string")) @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ReviewRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        guard.limitHub(API_REVIEW);
        ArchiveEntryChecks.requireId(id, "requestNo");
        SkuArchiveAction action = ReviewActions.parseArchiveAction(request.action());
        ArchiveEntryChecks.requireReviewReason(action, request.reviewReason());
        boolean approve = action == SkuArchiveAction.APPROVE;
        return ArchiveHttp.accepted(acceptance.submit(
            AcceptanceCommand.of(HUB_SCOPE, API_REVIEW, idempotencyKey, id,
                request.toString(), SkuChangeRequestStatus.PENDING.name(), user.accountId()),
            () -> changeRequestService.review(id, approve, request.reviewReason(), user.accountId())));
    }

    /**
     * API-013 请求体：动作与驳回原因。
     *
     * <p>驳回原因按契约字段表的 {@code reviewReason} 收（与 API-008 同口径，与批量接口的
     * {@code reason} 区分）。
     *
     * <p>动作闭集写进 OpenAPI：申请审核与 API-008 一样只有通过与驳回，
     * 与 {@link ReviewActions#parseArchiveAction(String)} 同一份口径。
     */
    public record ReviewRequest(
            @Schema(allowableValues = {"APPROVE", "REJECT"},
                description = "审核动作：APPROVE 通过、REJECT 驳回；闭集外返回 PARAM_INVALID")
            String action,
            String reviewReason) {

        /** 未知字段一律拒绝：审核只接受动作与驳回原因两个字段。 */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw ArchiveRequestBody.unknownField(fieldName);
        }
    }
}
