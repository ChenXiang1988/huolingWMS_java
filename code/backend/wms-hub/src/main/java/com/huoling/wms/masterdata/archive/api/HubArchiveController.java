package com.huoling.wms.masterdata.archive.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huoling.wms.masterdata.acceptance.application.AcceptanceCommand;
import com.huoling.wms.masterdata.acceptance.application.AcceptanceService;
import com.huoling.wms.masterdata.archive.application.ArchiveAccessGuard;
import com.huoling.wms.masterdata.archive.application.SkuArchiveCommandService;
import com.huoling.wms.masterdata.archive.application.SkuArchiveQueryService;
import com.huoling.wms.masterdata.archive.application.SkuReviewService;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchivePermissionCodes;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveQuery;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 运营端商品档案接口（API-006 至 API-009），路径前缀 {@code /api/hub}。
 *
 * <p>数据域为全量，但代建不改变归属：归属由请求体指定，运营只获得管理视图（specs product-archive-access）。
 * 写接口幂等作用域为运营端（{@code HUB}）加接口加键，作用域内同键只受理一次。
 *
 * <p>写接口统一 202 受理：批量与单条都不在响应里回逐条结果，
 * 逐条明细由 API-016 按受理号查询（契约 API-009 响应）。
 */
@RestController
@RequestMapping("/api/hub/archives")
public class HubArchiveController {

    private static final String API_CREATE = "API-006";
    private static final String API_QUERY = "API-007";
    private static final String API_REVIEW = "API-008";
    private static final String API_BATCH = "API-009";
    private static final String HUB_SCOPE = AcceptanceService.SCOPE_HUB;

    private final SkuArchiveCommandService commandService;
    private final SkuArchiveQueryService queryService;
    private final SkuReviewService reviewService;
    private final ArchiveAccessGuard guard;
    private final AcceptanceService acceptance;
    private final JwtTokenService tokenService;

    public HubArchiveController(SkuArchiveCommandService commandService,
                                SkuArchiveQueryService queryService,
                                SkuReviewService reviewService,
                                ArchiveAccessGuard guard,
                                AcceptanceService acceptance,
                                JwtTokenService tokenService) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.reviewService = reviewService;
        this.guard = guard;
        this.acceptance = acceptance;
        this.tokenService = tokenService;
    }

    /** API-006：运营代建草稿，归属货主必填；入口同步校验，不进入异步受理。 */
    @PostMapping
    @RequiresPermission(ArchivePermissionCodes.HUB_CREATE)
    @ApiResponses({
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_ACCEPTED,
            description = ArchiveApiDocs.ACCEPTED,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_BAD_REQUEST,
            description = ArchiveApiDocs.PARAM_INVALID,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_FORBIDDEN,
            description = ArchiveApiDocs.FORBIDDEN_OWNER,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_CONFLICT,
            description = "IDEMPOTENT_IN_PROGRESS、IDEMPOTENT_KEY_CONFLICT：幂等记录处理中或同键异体",
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
        guard.limitHub(API_CREATE);
        Long ownerId = ArchiveIdentifiers.parseOrNull(request.ownerId(), "ownerId");
        if (ownerId == null) {
            // 代建不改归属、也不允许无归属落档：未指定货主在入口按越权拒绝（契约 API-006 错误码）。
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "代建必须指定归属货主 ownerId");
        }
        request.toFields().requireComplete();
        String scope = String.valueOf(ownerId);
        return ArchiveHttp.accepted(acceptance.submit(
            AcceptanceCommand.of(scope, API_CREATE, idempotencyKey, "create",
                request.toString(), SkuArchiveStatus.DRAFT.name(), user.accountId()),
            () -> commandService.createDraftByHub(ownerId, request.toFields(), user.accountId())));
    }

    /** API-007：全量查询，可按归属货主过滤。 */
    @GetMapping
    @RequiresPermission(ArchivePermissionCodes.HUB_READ)
    public ArchiveResponse<OwnerArchiveController.ArchiveListResponse> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String ownerCode,
            @RequestParam(required = false) SkuArchiveStatus status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Instant updatedFrom,
            @RequestParam(required = false) Instant updatedTo,
            @Parameter(description = "排序字段：updateTime、ownerCode、name",
                schema = @Schema(type = "string")) @RequestParam(required = false) String sortBy,
            @Parameter(description = "排序方向：asc、desc，缺省 desc",
                schema = @Schema(type = "string")) @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        CurrentUser.from(tokenService, authorization);
        guard.limitHub(API_QUERY);
        requireLimit(limit);
        Long ownerFilter = ArchiveIdentifiers.parseOrNull(ownerId, "ownerId");
        SkuListSort sort = SkuListSort.parse(sortBy, sortDir);
        SkuArchiveQueryService.ArchivePage page = queryService.pageForHub(ownerFilter, ownerCode, null,
            status, category, updatedFrom, updatedTo, sort, cursor, limit);
        return ArchiveResponse.ok(OwnerArchiveController.ArchiveListResponse.of(page));
    }

    /** API-008：单条审核，受理时状态快照为待审核；动作闭集与驳回原因在入口校验。 */
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
            @Parameter(description = "档案编号", schema = @Schema(type = "string")) @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ReviewRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        guard.limitHub(API_REVIEW);
        ArchiveEntryChecks.requireId(id, "archiveId");
        SkuArchiveAction action = ReviewActions.parseArchiveAction(request.action());
        ArchiveEntryChecks.requireReviewReason(action, request.reviewReason());
        return ArchiveHttp.accepted(acceptance.submit(
            AcceptanceCommand.of(HUB_SCOPE, API_REVIEW, idempotencyKey, id,
                request.toString(), SkuArchiveStatus.PENDING.name(), user.accountId()),
            () -> reviewService.review(id, action, request.reviewReason(), user.accountId())));
    }

    /** API-009：批量处理（通过、驳回或停用），逐条结果写进受理结果摘要，由 API-016 查询。 */
    @PostMapping("/batch")
    @RequiresPermission(ArchivePermissionCodes.HUB_REVIEW)
    @ApiResponses({
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_ACCEPTED,
            description = ArchiveApiDocs.ACCEPTED,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_BAD_REQUEST,
            description = ArchiveApiDocs.PARAM_INVALID + "；单次上限一百条",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_NOT_FOUND,
            description = ArchiveApiDocs.NOT_FOUND_ARCHIVE + "；逐条结论同时写进受理结果摘要",
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_CONFLICT,
            description = ArchiveApiDocs.CONFLICT + "；逐条结论同时写进受理结果摘要，单条失败不影响其他条目",
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
    public ResponseEntity<ArchiveResponse<AcceptanceService.Ticket>> batch(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody BatchRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        guard.limitHub(API_BATCH);
        SkuArchiveAction action = ReviewActions.parseBatchAction(request.action());
        List<String> archiveIds = ArchiveEntryChecks.requireBatchIds(request.archiveIds());
        ArchiveEntryChecks.requireBatchReason(action, request.reason());
        return ArchiveHttp.accepted(acceptance.submit(
            AcceptanceCommand.of(HUB_SCOPE, API_BATCH, idempotencyKey, HUB_SCOPE,
                request.toString(), snapshotOf(action), user.accountId()),
            () -> reviewService.batch(archiveIds, action, request.reason(), user.accountId())));
    }

    /**
     * 批量受理时的状态快照（契约「写接口统一响应」只回受理时状态快照）。
     *
     * <p>通过与驳回作用于待审核，快照取 {@code PENDING}；停用只作用于启用态，
     * 快照取 {@code ACTIVE}（与 API-004 一致）。批量里条目状态可能不一致，
     * 这里给出的是该动作的受理前置状态，不是最终状态。
     */
    private String snapshotOf(SkuArchiveAction action) {
        return action == SkuArchiveAction.DISABLE
            ? SkuArchiveStatus.ACTIVE.name() : SkuArchiveStatus.PENDING.name();
    }

    private void requireLimit(int limit) {
        if (limit <= 0 || limit > SkuArchiveQuery.MAX_LIMIT) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + SkuArchiveQuery.MAX_LIMIT);
        }
    }

    /** API-006 请求体：归属货主加档案字段（规格型号字段名为 {@code spec}）。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateRequest(
            String ownerId, String name, String ownerCode, String barcode, String baseUnit,
            String storageCondition, String category, String spec, String brand,
            String barcodeType, String packageSpec, BigDecimal packageConversionRate,
            BigDecimal netWeight, BigDecimal grossWeight, BigDecimal volume, BigDecimal length,
            BigDecimal width, BigDecimal height, Boolean highValue, Boolean hazardous, Boolean fragile,
            Boolean batchManaged, Boolean serialManaged, Integer shelfLifeDays, String imageUrl,
            String remark) implements ArchiveFieldSource {

        /** 未知字段一律拒绝（契约 API-006「请求」）：库位关联字段不属于商品档案模型。 */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw ArchiveRequestBody.unknownField(fieldName);
        }
    }

    /**
     * API-008 请求体：动作与驳回原因。
     *
     * <p>驳回原因的线上字段名是契约字段表的 {@code reviewReason}（不是批量接口的 {@code reason}）：
     * 两个接口的字段名不同，审核侧按 {@code reviewReason} 收，批量侧按 {@code reason} 收。
     *
     * <p>动作闭集写进 OpenAPI（`01`「接口契约由服务端导出」）：单条审核只有通过与驳回，
     * 与 {@link ReviewActions#parseArchiveAction(String)} 同一份口径，导出物才能看出闭集边界。
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

    /**
     * API-009 请求体：条目编号数组、动作与驳回原因（契约字段名 {@code archiveId} 的数组）。
     *
     * <p>批量动作闭集比单条审核多一个停用，与 {@link ReviewActions#parseBatchAction(String)} 同一份口径。
     */
    public record BatchRequest(
            List<String> archiveIds,
            @Schema(allowableValues = {"APPROVE", "REJECT", "DISABLE"},
                description = "批量动作：APPROVE 通过、REJECT 驳回、DISABLE 停用；闭集外返回 PARAM_INVALID")
            String action,
            String reason) {

        /** 未知字段一律拒绝：批量只接受条目数组、动作与原因三个字段。 */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw ArchiveRequestBody.unknownField(fieldName);
        }
    }
}
