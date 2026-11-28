package com.huoling.wms.masterdata.identity.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.huoling.wms.masterdata.identity.application.AccountCommandService;
import com.huoling.wms.masterdata.identity.application.AccountQueryService;
import com.huoling.wms.masterdata.identity.application.AcceptanceService;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 账号接口：查询（API-005、API-007）与写入（API-006、API-008、API-009）。
 * 写入只返回受理号，结果由受理结果查询接口给出（D20）。
 *
 * <p>标识在边界转换（契约「标识、类型与枚举约定」）：出参 {@code id}、{@code employeeId}
 * 一律十进制字符串，入参路径参数与请求体同样只收字符串，数字形态按 {@code COMMON_9001} 拒绝。
 */
@RestController
@RequestMapping("/hub/api/accounts")
public class AccountController {

    /** 契约 API-008：{@code resetPassword} 长度上限 72，超出 BCrypt 有效长度。 */
    private static final int PASSWORD_MAX_LENGTH = 72;

    private final AccountQueryService queryService;
    private final AccountCommandService commandService;
    private final AcceptanceService acceptanceService;
    private final JwtTokenService tokenService;

    public AccountController(AccountQueryService queryService,
                             AccountCommandService commandService,
                             AcceptanceService acceptanceService,
                             JwtTokenService tokenService) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.acceptanceService = acceptanceService;
        this.tokenService = tokenService;
    }

    @GetMapping
    @RequiresPermission("hub.account.read")
    public ApiResponse<PageResponse<AccountResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) RecordStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AccountQueryService.PageResult result = queryService.list(keyword, status, page, size);
        List<AccountResponse> items = result.items().stream().map(AccountController::toResponse).toList();
        return ApiResponse.ok(new PageResponse<>(items, result.total(), result.page(), result.size()),
            traceId());
    }

    @GetMapping("/{id}")
    @RequiresPermission("hub.account.read")
    public ApiResponse<AccountResponse> detail(
            @Parameter(description = "账号主键，十进制字符串",
                schema = @Schema(type = "string", pattern = Identifiers.PATTERN))
            @PathVariable String id) {
        return ApiResponse.ok(toResponse(queryService.detail(Identifiers.parse(id, "id"))), traceId());
    }

    @PostMapping
    @RequiresPermission("hub.account.create")
    public ResponseEntity<ApiResponse<AcceptTicket>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody @Valid CreateRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        // 标识先按边界形态校验：数字形态在此拒绝为 COMMON_9001，不进入受理环。
        // 两个标识都可空（员工与货主二选一），空值不参与形态校验，由绑定规则判定该不该给。
        Long employeeId = request.employeeId() == null ? null
            : Identifiers.parse(request.employeeId(), "employeeId");
        Long ownerId = request.ownerId() == null ? null : Identifiers.parse(request.ownerId(), "ownerId");
        List<Long> roleIds = Identifiers.parseAll(request.roleIds(), "roleIds");
        ClientType client = parseClient(request.client());
        // 绑定在入口先判一次：契约 1.7 要求「缺货主 / 货主不存在」直接 400，不进入受理环。
        commandService.assertAccountBinding(client, employeeId, ownerId);
        // 角色端标识同样在入口判：货主端账号挂运营端角色（或反之）是参数错误，
        // 按既有 ROLE_4002 直接拒绝，不创建账号、不占用受理号。
        commandService.assertAccountRoles(client, roleIds);
        String acceptNo = acceptanceService.submit(user.accountId(), "API-006", "/hub/api/accounts",
            idempotencyKey, request.toString(),
            () -> commandService.create(user.accountId(),
                new AccountCommandService.CreateCommand(request.accountName(), employeeId, client,
                    ownerId, request.initialPassword(), roleIds)));
        return accepted(acceptNo);
    }

    /**
     * 契约 1.7：{@code client} 必填，取值只有 {@code HUB} 与 {@code OWNER}。
     *
     * <p>不复用 {@link ClientType} 全量取值：{@code SITE} 与 {@code MOBILE} 是单仓端与移动作业端的端标识，
     * 开通账号时传入它们必然开出一个登不进去的账号，属参数错误而非业务错误。
     */
    private ClientType parseClient(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badRequest("字段 client 必填，取值 HUB 或 OWNER");
        }
        if (ClientType.HUB.name().equals(raw)) {
            return ClientType.HUB;
        }
        if (ClientType.OWNER.name().equals(raw)) {
            return ClientType.OWNER;
        }
        throw badRequest("字段 client 取值只能是 HUB 或 OWNER，收到 \"" + raw + "\"");
    }

    @PatchMapping("/{id}")
    @RequiresPermission("hub.account.update")
    public ResponseEntity<ApiResponse<AcceptTicket>> update(
            @Parameter(description = "账号主键，十进制字符串",
                schema = @Schema(type = "string", pattern = Identifiers.PATTERN))
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody UpdateRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        long accountId = Identifiers.parse(id, "id");
        validateUpdate(request);
        Long ownerId = request.ownerId() == null ? null : Identifiers.parse(request.ownerId(), "ownerId");
        // 绑定不可变在入口先判一次：契约 1.7 要求「携带不同 ownerId 即拒绝」返回 400。
        commandService.assertOwnerUnchanged(accountId, ownerId);
        String acceptNo = acceptanceService.submit(user.accountId(), "API-008",
            "/hub/api/accounts/" + id, idempotencyKey, request.toString(),
            () -> commandService.update(user.accountId(), accountId,
                new AccountCommandService.UpdateCommand(request.status(), request.resetPassword(),
                    ownerId, request.version())));
        return accepted(acceptNo);
    }

    /**
     * 契约 API-008 的入口拒绝：{@code version} 必填；{@code status} 与 {@code resetPassword}
     * 互斥且必须给出一项；{@code resetPassword} 非空、长度上限 72（BCrypt 有效长度）。
     * 一律 400 {@code COMMON_9001}，不进入受理环、不落库。
     */
    private void validateUpdate(UpdateRequest request) {
        if (request.version() == null) {
            throw badRequest("字段 version 必填");
        }
        boolean hasStatus = request.status() != null;
        boolean hasPassword = request.resetPassword() != null;
        if (hasStatus == hasPassword) {
            throw badRequest("字段 status 与 resetPassword 互斥，必须且只能给出一项");
        }
        if (hasPassword) {
            if (request.resetPassword().isBlank()) {
                throw badRequest("字段 resetPassword 不能为空");
            }
            if (request.resetPassword().length() > PASSWORD_MAX_LENGTH) {
                throw badRequest("字段 resetPassword 长度上限 " + PASSWORD_MAX_LENGTH);
            }
        }
    }

    private IdentityException badRequest(String message) {
        return new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, message);
    }

    @PutMapping("/{id}/roles")
    @RequiresPermission("hub.account.role")
    public ResponseEntity<ApiResponse<AcceptTicket>> assignRoles(
            @Parameter(description = "账号主键，十进制字符串",
                schema = @Schema(type = "string", pattern = Identifiers.PATTERN))
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AssignRolesRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        long accountId = Identifiers.parse(id, "id");
        List<Long> roleIds = Identifiers.parseAll(request.roleIds(), "roleIds");
        if (request.version() == null) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, "字段 version 必填");
        }
        // 角色端标识在入口按目标账号实际所在端判一次（契约 API-009 的 ROLE_4002）：与 API-006 同一口径，
        // 端不符属参数错误，入口直接 400 且不占用受理号。只把判定放在受理执行里，
        // 调用方拿到的是 202 与一条事后才失败的受理单。
        commandService.assertAccountRoles(queryService.clientOf(accountId), roleIds);
        String acceptNo = acceptanceService.submit(user.accountId(), "API-009",
            "/hub/api/accounts/" + id + "/roles", idempotencyKey, request.toString(),
            () -> commandService.assignRoles(user.accountId(), accountId, roleIds, request.version()));
        return accepted(acceptNo);
    }

    private ResponseEntity<ApiResponse<AcceptTicket>> accepted(String acceptNo) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.ok(new AcceptTicket(acceptNo), traceId()));
    }

    private String traceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 契约 API-005、API-007 的账号条目：{@code id} 与 {@code employeeId} 为十进制字符串。
     *
     * <p>本次变更按契约 1.7 扩展四个字段：{@code client}（端标识）、{@code ownerId}（归属货主）、
     * {@code ownerNo} 与 {@code ownerName}（由服务端按 {@code ownerId} 关联货主档案取得）。
     * 非货主端账号的 {@code ownerId}、{@code ownerNo}、{@code ownerName} 为空，前端显示「—」；
     * {@code client} 恒返回端标识，前端据此渲染「端」列而不必从其它字段反推。
     */
    public record AccountResponse(
            @Schema(type = "string", pattern = Identifiers.PATTERN, description = "账号主键，十进制字符串")
            String id,
            String accountName,
            @Schema(type = "string", pattern = Identifiers.PATTERN, nullable = true,
                description = "员工内部主键，十进制字符串；货主端账号不绑员工，为 null")
            String employeeId,
            @Schema(description = "端标识：运营端 HUB、货主端 OWNER（契约 1.7）",
                allowableValues = {"HUB", "OWNER"})
            String client,
            @Schema(type = "string", pattern = Identifiers.PATTERN, nullable = true,
                description = "归属货主，十进制字符串；非货主端账号为 null")
            String ownerId,
            @Schema(nullable = true, description = "归属货主编号；非货主端账号或档案缺失为 null")
            String ownerNo,
            @Schema(nullable = true, description = "归属货主名称；非货主端账号或档案缺失为 null")
            String ownerName,
            RecordStatus status,
            List<String> roles,
            long version) {
    }

    private static AccountResponse toResponse(AccountQueryService.AccountView view) {
        return new AccountResponse(Identifiers.toWire(view.id()), view.accountName(),
            Identifiers.toWire(view.employeeId()), view.client().name(),
            Identifiers.toWire(view.ownerId()), view.ownerNo(), view.ownerName(), view.status(),
            view.roles(), view.version());
    }

    /**
     * 契约 API-006 请求体。{@code employeeId}、{@code ownerId} 与 {@code roleIds[]}
     * 只接受十进制字符串，JSON 数字形态在 Jackson 反序列化阶段即被拒（类型不符，{@code COMMON_9001}）。
     *
     * <p>{@code client} 与 {@code ownerId} 是本次变更的字段扩展（契约 1.7）：
     * {@code client=OWNER} 时 {@code ownerId} 必填且该货主档案必须存在；{@code client=HUB} 时不得出现。
     * 两个字段都交应用层判定，入口只做形态与闭集校验——「档案是否存在」要查库，属业务判定。
     */
    public record CreateRequest(
            @NotBlank String accountName,
            @JsonDeserialize(using = IdentifierDeserializer.class)
            @Schema(type = "string", pattern = Identifiers.PATTERN, nullable = true,
                description = "员工内部主键，十进制字符串；client=HUB 时必填，货主端账号不绑员工；"
                    + "传 JSON 数字返回 COMMON_9001")
            String employeeId,
            @Schema(description = "端标识：HUB 或 OWNER，必填（契约 1.7）",
                allowableValues = {"HUB", "OWNER"})
            String client,
            @JsonDeserialize(using = IdentifierDeserializer.class)
            @Schema(type = "string", pattern = Identifiers.PATTERN, nullable = true,
                description = "归属货主，十进制字符串；client=OWNER 时必填，client=HUB 时不得出现")
            String ownerId,
            @NotBlank String initialPassword,
            @JsonDeserialize(contentUsing = IdentifierDeserializer.class)
            @ArraySchema(schema = @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "角色主键，十进制字符串"), minItems = 0)
            List<String> roleIds) {
    }

    /**
     * 契约 API-008 请求体：{@code {status?, resetPassword?, ownerId?, version}}，
     * 两个写字段互斥，{@code version} 必填。
     *
     * <p>{@code ownerId} 可选：不传表示不改归属货主；传了且与现值不同即拒绝（绑定不可改，契约 1.7）。
     */
    public record UpdateRequest(RecordStatus status, String resetPassword,
                                @JsonDeserialize(using = IdentifierDeserializer.class)
                                @Schema(type = "string", pattern = Identifiers.PATTERN, nullable = true,
                                    description = "归属货主，十进制字符串；已绑定的账号传不同值即拒绝")
                                String ownerId,
                                Long version) {
    }

    /** 契约 API-009 请求体：{@code roleIds[]} 只接受十进制字符串数组，整体替换。 */
    public record AssignRolesRequest(
            @JsonDeserialize(contentUsing = IdentifierDeserializer.class)
            @ArraySchema(schema = @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "角色主键，十进制字符串"), minItems = 0)
            List<String> roleIds,
            Long version) {
    }

    public record AcceptTicket(String acceptNo) {
    }
}
