package com.huoling.wms.masterdata.archive.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huoling.wms.masterdata.archive.application.ArchiveAcceptanceService;
import com.huoling.wms.masterdata.archive.application.ArchiveAccessGuard;
import com.huoling.wms.masterdata.archive.application.SkuArchiveCommandService;
import com.huoling.wms.masterdata.archive.application.SkuArchiveQueryService;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchivePermissionCodes;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveQuery;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 货主端商品档案接口（API-001 至 API-005），路径前缀 {@code /api/owner}。
 *
 * <p>数据域：归属货主取登录账号绑定的货主，取不到即 {@code FORBIDDEN_OWNER}，
 * 仓储层把它强制拼进查询条件（INV-06、D02）；代码里没有内部货主分支（D03）。
 *
 * <p>写接口统一 202 受理（契约「写接口统一响应」）：响应只含 {@code acceptNo} 与受理时状态快照，
 * 结果由 API-015 按受理号查询，不在提交时伪造成功，也不回最终版本号。
 */
@RestController
@RequestMapping("/api/owner/archives")
public class OwnerArchiveController {

    private static final String API_CREATE = "API-001";
    private static final String API_SUBMIT = "API-002";
    private static final String API_EDIT = "API-003";
    private static final String API_DISABLE = "API-004";
    private static final String API_QUERY = "API-005";

    private final SkuArchiveCommandService commandService;
    private final SkuArchiveQueryService queryService;
    private final ArchiveAccessGuard guard;
    private final ArchiveAcceptanceService acceptance;
    private final JwtTokenService tokenService;

    public OwnerArchiveController(SkuArchiveCommandService commandService,
                                 SkuArchiveQueryService queryService,
                                 ArchiveAccessGuard guard,
                                 ArchiveAcceptanceService acceptance,
                                 JwtTokenService tokenService) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.guard = guard;
        this.acceptance = acceptance;
        this.tokenService = tokenService;
    }

    /** API-001：创建草稿，返回 202、受理号与受理时状态快照；必填与长度在入口校验。 */
    @PostMapping
    @RequiresPermission(ArchivePermissionCodes.OWNER_WRITE)
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
    public ResponseEntity<ArchiveResponse<ArchiveAcceptanceService.Ticket>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody CreateRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        Long ownerId = guard.requireOwner(user.accountId(), user.client());
        guard.limitOwner(ownerId, API_CREATE);
        request.toFields().requireComplete();
        String scope = String.valueOf(ownerId);
        return ArchiveHttp.accepted(acceptance.submit(
            new ArchiveAcceptanceService.Command(scope, API_CREATE, idempotencyKey, "create",
                request.toString(), SkuArchiveStatus.DRAFT.name(), user.accountId()),
            () -> commandService.createDraft(ownerId, request.toFields(), user.accountId())));
    }

    /** API-005：查询自有档案，键集分页；排序字段与方向取契约闭集。 */
    @GetMapping
    @RequiresPermission(ArchivePermissionCodes.OWNER_READ)
    public ArchiveResponse<ArchiveListResponse> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String ownerCode,
            @RequestParam(required = false) String name,
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
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        Long ownerId = guard.requireOwner(user.accountId(), user.client());
        guard.limitOwner(ownerId, API_QUERY);
        requireLimit(limit);
        SkuListSort sort = SkuListSort.parse(sortBy, sortDir);
        SkuArchiveQueryService.ArchivePage page = queryService.pageForOwner(ownerId, ownerCode, name,
            status, category, updatedFrom, updatedTo, sort, cursor, limit);
        return ArchiveResponse.ok(ArchiveListResponse.of(page));
    }

    /** API-003：编辑草稿或驳回态档案，启用态字段变更必须走变更申请或审核流程。 */
    @PutMapping("/{id}")
    @RequiresPermission(ArchivePermissionCodes.OWNER_WRITE)
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
    public ResponseEntity<ArchiveResponse<ArchiveAcceptanceService.Ticket>> edit(
            @Parameter(description = "档案编号", schema = @Schema(type = "string")) @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody EditRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        Long ownerId = guard.requireOwner(user.accountId(), user.client());
        guard.limitOwner(ownerId, API_EDIT);
        ArchiveEntryChecks.requireId(id, "archiveId");
        ArchiveEntryChecks.requireVersion(request.version(), "version");
        request.toFields().requireComplete();
        String scope = String.valueOf(ownerId);
        return ArchiveHttp.accepted(acceptance.submit(
            new ArchiveAcceptanceService.Command(scope, API_EDIT, idempotencyKey, id,
                request.toString(), SkuArchiveStatus.DRAFT.name(), user.accountId()),
            () -> commandService.edit(id, ownerId, request.version(), request.toFields(),
                user.accountId())));
    }

    /** API-002：提交审核。请求体可空，带版本号时做乐观锁校验。 */
    @PostMapping("/{id}/submit")
    @RequiresPermission(ArchivePermissionCodes.OWNER_WRITE)
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
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_NOT_FOUND,
            description = ArchiveApiDocs.NOT_FOUND_ARCHIVE,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_CONFLICT,
            description = ArchiveApiDocs.CONFLICT,
            content = @Content(schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = ArchiveApiDocs.CODE_UNPROCESSABLE,
            description = ArchiveApiDocs.DUPLICATE,
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
    public ResponseEntity<ArchiveResponse<ArchiveAcceptanceService.Ticket>> submit(
            @Parameter(description = "档案编号", schema = @Schema(type = "string")) @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) SubmitRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        Long ownerId = guard.requireOwner(user.accountId(), user.client());
        guard.limitOwner(ownerId, API_SUBMIT);
        ArchiveEntryChecks.requireId(id, "archiveId");
        SubmitRequest body = request == null ? new SubmitRequest(null) : request;
        String scope = String.valueOf(ownerId);
        return ArchiveHttp.accepted(acceptance.submit(
            new ArchiveAcceptanceService.Command(scope, API_SUBMIT, idempotencyKey, id,
                body.toString(), SkuArchiveStatus.DRAFT.name(), user.accountId()),
            () -> commandService.submit(id, ownerId, body.version(), user.accountId())));
    }

    /** API-004：逻辑停用。删除即停用，数据保留可追溯（INV-18）。 */
    @PostMapping("/{id}/disable")
    @RequiresPermission(ArchivePermissionCodes.OWNER_WRITE)
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
    public ResponseEntity<ArchiveResponse<ArchiveAcceptanceService.Ticket>> disable(
            @Parameter(description = "档案编号", schema = @Schema(type = "string")) @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody DisableRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        Long ownerId = guard.requireOwner(user.accountId(), user.client());
        guard.limitOwner(ownerId, API_DISABLE);
        ArchiveEntryChecks.requireId(id, "archiveId");
        ArchiveEntryChecks.requireVersion(request.version(), "version");
        ArchiveEntryChecks.requireText(request.reason(), "reason", ArchiveEntryChecks.REASON_MAX);
        String scope = String.valueOf(ownerId);
        return ArchiveHttp.accepted(acceptance.submit(
            new ArchiveAcceptanceService.Command(scope, API_DISABLE, idempotencyKey, id,
                request.toString(), SkuArchiveStatus.ACTIVE.name(), user.accountId()),
            () -> commandService.disable(id, ownerId, request.version(), request.reason(),
                user.accountId())));
    }

    private void requireLimit(int limit) {
        if (limit <= 0 || limit > SkuArchiveQuery.MAX_LIMIT) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + SkuArchiveQuery.MAX_LIMIT);
        }
    }

    /** API-001 请求体：未知字段一并拒绝（契约「请求」约定）；规格型号字段名为 {@code spec}。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateRequest(
            String name, String ownerCode, String barcode, String baseUnit, String storageCondition,
            String category, String spec, String brand, String barcodeType, String packageSpec,
            BigDecimal packageConversionRate, BigDecimal netWeight, BigDecimal grossWeight,
            BigDecimal volume, BigDecimal length, BigDecimal width, BigDecimal height, Boolean highValue,
            Boolean hazardous, Boolean fragile, Boolean batchManaged, Boolean serialManaged,
            Integer shelfLifeDays, String imageUrl, String remark) implements ArchiveFieldSource {

        /** 未知字段一律拒绝：库位关联字段不在档案模型内。 */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw ArchiveRequestBody.unknownField(fieldName);
        }
    }

    /** API-003 请求体：允许修改字段加版本号。 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EditRequest(
            String name, String ownerCode, String barcode, String baseUnit, String storageCondition,
            String category, String spec, String brand, String barcodeType, String packageSpec,
            BigDecimal packageConversionRate, BigDecimal netWeight, BigDecimal grossWeight,
            BigDecimal volume, BigDecimal length, BigDecimal width, BigDecimal height, Boolean highValue,
            Boolean hazardous, Boolean fragile, Boolean batchManaged, Boolean serialManaged,
            Integer shelfLifeDays, String imageUrl, String remark,
            Long version) implements ArchiveFieldSource {

        /** 未知字段一律拒绝：编辑只接受允许修改的档案字段与版本号。 */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw ArchiveRequestBody.unknownField(fieldName);
        }
    }

    /** API-002 请求体：可空，只带可选的版本号。 */
    public record SubmitRequest(Long version) {

        /** 未知字段一律拒绝：提交只接受版本号。 */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw ArchiveRequestBody.unknownField(fieldName);
        }
    }

    /** API-004 请求体：停用原因与版本号。 */
    public record DisableRequest(String reason, Long version) {

        /** 未知字段一律拒绝：停用只接受原因与版本号。 */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw ArchiveRequestBody.unknownField(fieldName);
        }
    }

    /** 列表响应：条目、下一页游标、是否还有数据与数据时间戳。 */
    public record ArchiveListResponse(List<ArchiveSummaryView> items, String nextCursor,
                                      boolean hasMore, Instant dataTimestamp) {

        static ArchiveListResponse of(SkuArchiveQueryService.ArchivePage page) {
            return new ArchiveListResponse(page.items().stream().map(ArchiveSummaryView::of).toList(),
                page.nextCursor(), page.hasMore(), page.dataTimestamp());
        }
    }
}
