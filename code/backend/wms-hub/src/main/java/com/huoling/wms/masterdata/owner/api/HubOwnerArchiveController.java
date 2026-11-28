package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.masterdata.acceptance.application.AcceptanceCommand;
import com.huoling.wms.masterdata.acceptance.application.AcceptanceService;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.identity.api.CurrentUser;
import com.huoling.wms.masterdata.identity.api.RequiresPermission;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import com.huoling.wms.masterdata.owner.application.OwnerAcceptanceQueries;
import com.huoling.wms.masterdata.owner.application.OwnerAccessGuard;
import com.huoling.wms.masterdata.owner.application.OwnerCommandService;
import com.huoling.wms.masterdata.owner.application.OwnerMetrics;
import com.huoling.wms.masterdata.owner.application.OwnerQueryService;
import com.huoling.wms.masterdata.owner.domain.OwnerArchive;
import com.huoling.wms.masterdata.owner.domain.OwnerPermissionAlias;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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

import java.time.Instant;

/**
 * 运营端货主档案接口（API-021 至 API-026），路径前缀 {@code /api/hub/owner-archives}。
 *
 * <p>鉴权两道门都在服务端：功能权限 {@code owner.hub.read} / {@code owner.hub.write} 由
 * {@code @RequiresPermission} 的切面校验；货主数据域由 {@link OwnerAccessGuard} 解析后在仓储层注入。
 * 端标识不是 {@code HUB}（货主端、单仓端令牌）在第一道门就被拒（契约 1.1「端与令牌的强校验」）。
 *
 * <p>四个写接口一律 202 受理：入口只做字段级校验（未知字段、四项必填、原因与版本号必填、
 * 请求体与分页边界），存在性、状态守卫、版本比对与信用代码判重全部留到受理后，
 * 结论经 API-027 返回（design「业务校验的时点」）。控制器里没有业务判定。
 *
 * <p>幂等键取请求头 {@code Idempotency-Key}，作用域为 {@code HUB} 加接口号加键，窗口 24 小时，
 * 同键同体回放首次受理号（{@code replayed=true}），同键异体 409（契约 1.3）。
 */
@RestController
@RequestMapping("/api/hub/owner-archives")
public class HubOwnerArchiveController {

    private static final String API_LIST = "API-021";
    private static final String API_DETAIL = "API-022";
    private static final String HUB_SCOPE = AcceptanceService.SCOPE_HUB;
    private static final String CREATE_TARGET = "create";

    private final OwnerQueryService queryService;
    private final OwnerCommandService commandService;
    private final OwnerAccessGuard guard;
    private final AcceptanceService acceptance;
    private final OwnerMetrics metrics;
    private final JwtTokenService tokenService;

    public HubOwnerArchiveController(OwnerQueryService queryService,
                                     OwnerCommandService commandService,
                                     OwnerAccessGuard guard,
                                     AcceptanceService acceptance,
                                     OwnerMetrics metrics,
                                     JwtTokenService tokenService) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.guard = guard;
        this.acceptance = acceptance;
        this.metrics = metrics;
        this.tokenService = tokenService;
    }

    /**
     * API-021：四类筛选（编号精确、名称模糊、信用代码精确、状态）加最后修改时间闭区间，
     * 排序白名单 {@code ownerNo}、{@code ownerName}、{@code updateTime}，键集分页。
     */
    @GetMapping
    @RequiresPermission(OwnerPermissionAlias.READ)
    public OwnerResponse<OwnerArchiveViews.OwnerPage> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Parameter(description = "货主编号，精确匹配", schema = @Schema(type = "string"))
            @RequestParam(required = false) String ownerNo,
            @Parameter(description = "货主名称，模糊匹配", schema = @Schema(type = "string"))
            @RequestParam(required = false) String ownerName,
            @Parameter(description = "统一社会信用代码，精确匹配", schema = @Schema(type = "string"))
            @RequestParam(required = false) String creditCode,
            @RequestParam(required = false) OwnerStatus status,
            @RequestParam(required = false) Instant updatedFrom,
            @RequestParam(required = false) Instant updatedTo,
            @Parameter(description = "排序字段：ownerNo、ownerName、updateTime，缺省 ownerNo",
                schema = @Schema(type = "string")) @RequestParam(required = false) String sortBy,
            @Parameter(description = "排序方向：asc、desc，缺省 asc",
                schema = @Schema(type = "string")) @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        OwnerEntryChecks.requireLimit(limit);
        OwnerEntryChecks.requireCursor(cursor);
        OwnerListSort sort = OwnerEntryChecks.requireSort(sortBy, sortDir);
        guard.limitHub(API_LIST);
        OwnerScope scope = guard.requireHubScope(user.accountId(), user.client());
        return OwnerResponse.ok(OwnerArchiveViews.OwnerPage.of(queryService.page(scope, ownerNo,
            ownerName, creditCode, status, updatedFrom, updatedTo, sort, cursor, limit)));
    }

    /** API-022：按货主编号取详情；域外与不存在同一响应（{@code NOT_FOUND_OWNER}）。 */
    @GetMapping("/{ownerNo}")
    @RequiresPermission(OwnerPermissionAlias.READ)
    public OwnerResponse<OwnerArchiveViews.OwnerDetail> detail(
            @Parameter(description = "货主编号", schema = @Schema(type = "string"))
            @PathVariable String ownerNo,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        OwnerEntryChecks.requireOwnerNo(ownerNo);
        guard.limitHub(API_DETAIL);
        OwnerScope scope = guard.requireHubScope(user.accountId(), user.client());
        OwnerArchive archive = queryService.detail(ownerNo, scope);
        return OwnerResponse.ok(OwnerArchiveViews.OwnerDetail.of(archive,
            queryService.operatorName(archive.lastStatusChange().operatorId())));
    }

    /** API-023：建档。要求全域数据域；编号与初始状态由系统给出，请求体传入即未知字段。 */
    @PostMapping
    @RequiresPermission(OwnerPermissionAlias.WRITE)
    public ResponseEntity<OwnerResponse<OwnerHttp.OwnerTicket>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody OwnerWriteRequests.OwnerCreateRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        // 请求体 16 KB 的上限已由 OwnerBodySizeFilter 在读请求体之前拦住：
        // 放到这里判，载荷已经被完整反序列化，等于「先用资源再拒绝」。
        OwnerFields fields = OwnerEntryChecks.requireFields(request.toFields());
        guard.limitHub(OwnerAcceptanceQueries.API_CREATE);
        OwnerScope scope = guard.requireHubScope(user.accountId(), user.client());
        guard.requireAllForCreate(scope);
        AcceptanceService.Submission submission = acceptance.submitWithReplay(
            AcceptanceCommand.of(HUB_SCOPE, OwnerAcceptanceQueries.API_CREATE, idempotencyKey,
                CREATE_TARGET, fingerprintOf(fields, null), OwnerStatus.ACTIVE.name(),
                user.accountId()),
            () -> metrics.timed(OwnerAcceptanceQueries.API_CREATE,
                () -> commandService.create(scope, fields, user.accountId())));
        metrics.acceptSubmitted(OwnerAcceptanceQueries.API_CREATE, submission.replayed());
        return OwnerHttp.accepted(submission);
    }

    /** API-024：改档。四项全量替换加版本号；编号与状态不在请求体内。 */
    @PutMapping("/{ownerNo}")
    @RequiresPermission(OwnerPermissionAlias.WRITE)
    public ResponseEntity<OwnerResponse<OwnerHttp.OwnerTicket>> edit(
            @Parameter(description = "货主编号", schema = @Schema(type = "string"))
            @PathVariable String ownerNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody OwnerWriteRequests.OwnerEditRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        OwnerEntryChecks.requireOwnerNo(ownerNo);
        OwnerFields fields = OwnerEntryChecks.requireFields(request.toFields());
        Long version = OwnerEntryChecks.requireVersion(request.version());
        guard.limitHub(OwnerAcceptanceQueries.API_EDIT);
        OwnerScope scope = guard.requireHubScope(user.accountId(), user.client());
        Long subjectOwnerId = queryService.subjectOwnerIdOf(ownerNo, scope);
        AcceptanceService.Submission submission = acceptance.submitWithReplay(
            AcceptanceCommand.ofOwner(HUB_SCOPE, OwnerAcceptanceQueries.API_EDIT, idempotencyKey,
                ownerNo, fingerprintOf(fields, version), OwnerStatus.ACTIVE.name(), user.accountId(),
                subjectOwnerId),
            () -> metrics.timed(OwnerAcceptanceQueries.API_EDIT,
                () -> commandService.edit(ownerNo, scope, version, fields, user.accountId())));
        metrics.acceptSubmitted(OwnerAcceptanceQueries.API_EDIT, submission.replayed());
        return OwnerHttp.accepted(submission);
    }

    /** API-025：停用。原因与版本号必填；受理时状态快照为可用。 */
    @PostMapping("/{ownerNo}/disable")
    @RequiresPermission(OwnerPermissionAlias.WRITE)
    public ResponseEntity<OwnerResponse<OwnerHttp.OwnerTicket>> disable(
            @Parameter(description = "货主编号", schema = @Schema(type = "string"))
            @PathVariable String ownerNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody OwnerWriteRequests.OwnerReasonRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        OwnerEntryChecks.requireOwnerNo(ownerNo);
        String reason = OwnerEntryChecks.requireReason(request.reason());
        Long version = OwnerEntryChecks.requireVersion(request.version());
        guard.limitHub(OwnerAcceptanceQueries.API_DISABLE);
        OwnerScope scope = guard.requireHubScope(user.accountId(), user.client());
        Long subjectOwnerId = queryService.subjectOwnerIdOf(ownerNo, scope);
        AcceptanceService.Submission submission = acceptance.submitWithReplay(
            AcceptanceCommand.ofOwner(HUB_SCOPE, OwnerAcceptanceQueries.API_DISABLE, idempotencyKey,
                ownerNo, fingerprintOf(reason, version), OwnerStatus.ACTIVE.name(), user.accountId(),
                subjectOwnerId),
            () -> metrics.timed(OwnerAcceptanceQueries.API_DISABLE,
                () -> commandService.disable(ownerNo, scope, version, reason, user.accountId())));
        metrics.acceptSubmitted(OwnerAcceptanceQueries.API_DISABLE, submission.replayed());
        return OwnerHttp.accepted(submission);
    }

    /** API-026：重新启用。原因与版本号必填；受理时状态快照为停用。 */
    @PostMapping("/{ownerNo}/enable")
    @RequiresPermission(OwnerPermissionAlias.WRITE)
    public ResponseEntity<OwnerResponse<OwnerHttp.OwnerTicket>> enable(
            @Parameter(description = "货主编号", schema = @Schema(type = "string"))
            @PathVariable String ownerNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody OwnerWriteRequests.OwnerReasonRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        OwnerEntryChecks.requireOwnerNo(ownerNo);
        String reason = OwnerEntryChecks.requireReason(request.reason());
        Long version = OwnerEntryChecks.requireVersion(request.version());
        guard.limitHub(OwnerAcceptanceQueries.API_ENABLE);
        OwnerScope scope = guard.requireHubScope(user.accountId(), user.client());
        Long subjectOwnerId = queryService.subjectOwnerIdOf(ownerNo, scope);
        AcceptanceService.Submission submission = acceptance.submitWithReplay(
            AcceptanceCommand.ofOwner(HUB_SCOPE, OwnerAcceptanceQueries.API_ENABLE, idempotencyKey,
                ownerNo, fingerprintOf(reason, version), OwnerStatus.DISABLED.name(),
                user.accountId(), subjectOwnerId),
            () -> metrics.timed(OwnerAcceptanceQueries.API_ENABLE,
                () -> commandService.enable(ownerNo, scope, version, reason, user.accountId())));
        metrics.acceptSubmitted(OwnerAcceptanceQueries.API_ENABLE, submission.replayed());
        return OwnerHttp.accepted(submission);
    }

    /**
     * 请求指纹的原文：只覆盖影响语义的内容（四项、版本号、原因），
     * 不掺时间戳、追踪号这类只影响传输的字段（契约 1.3「请求指纹」）。
     *
     * <p>用显式拼接而不是 {@code record.toString()}：后者一旦调整字段顺序，
     * 历史幂等记录的指纹就会全部对不上，同键重投会误判成「同键异体」。
     */
    private String fingerprintOf(OwnerFields fields, Long version) {
        return "ownerName=" + fields.ownerName()
            + "\ncontactName=" + fields.contactName()
            + "\ncontactPhone=" + fields.contactPhone()
            + "\ncreditCode=" + fields.creditCode()
            + "\nversion=" + (version == null ? "" : version);
    }

    /** 停用与启用的指纹：原因与版本号，字段名写死便于对账。 */
    private String fingerprintOf(String reason, Long version) {
        return "reason=" + reason + "\nversion=" + version;
    }
}
