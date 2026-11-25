package com.huoling.wms.masterdata.identity.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.huoling.wms.masterdata.identity.application.AccountCommandService;
import com.huoling.wms.masterdata.identity.application.AccountQueryService;
import com.huoling.wms.masterdata.identity.application.AcceptanceService;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        Long employeeId = Identifiers.parse(request.employeeId(), "employeeId");
        List<Long> roleIds = Identifiers.parseAll(request.roleIds(), "roleIds");
        String acceptNo = acceptanceService.submit(user.accountId(), "API-006", "/hub/api/accounts",
            idempotencyKey, request.toString(),
            () -> commandService.create(user.accountId(),
                new AccountCommandService.CreateCommand(request.accountName(), employeeId,
                    request.initialPassword(), roleIds)));
        return accepted(acceptNo);
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
        String acceptNo = acceptanceService.submit(user.accountId(), "API-008",
            "/hub/api/accounts/" + id, idempotencyKey, request.toString(),
            () -> commandService.update(user.accountId(), accountId,
                new AccountCommandService.UpdateCommand(request.status(), request.resetPassword(),
                    request.version())));
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

    /** 契约 API-005、API-007 的账号条目：{@code id} 与 {@code employeeId} 为十进制字符串。 */
    public record AccountResponse(
            @Schema(type = "string", pattern = Identifiers.PATTERN, description = "账号主键，十进制字符串")
            String id,
            String accountName,
            @Schema(type = "string", pattern = Identifiers.PATTERN, nullable = true,
                description = "员工内部主键，十进制字符串；货主端账号不绑员工，为 null")
            String employeeId,
            RecordStatus status,
            List<String> roles,
            long version) {
    }

    private static AccountResponse toResponse(AccountQueryService.AccountView view) {
        return new AccountResponse(Identifiers.toWire(view.id()), view.accountName(),
            Identifiers.toWire(view.employeeId()), view.status(), view.roles(), view.version());
    }

    /**
     * 契约 API-006 请求体。{@code employeeId} 与 {@code roleIds[]} 只接受十进制字符串，
     * JSON 数字形态在 Jackson 反序列化阶段即被拒（类型不符，{@code COMMON_9001}）。
     */
    public record CreateRequest(
            @NotBlank String accountName,
            @NotNull
            @JsonDeserialize(using = IdentifierDeserializer.class)
            @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "员工内部主键，十进制字符串；传 JSON 数字返回 COMMON_9001")
            String employeeId,
            @NotBlank String initialPassword,
            @JsonDeserialize(contentUsing = IdentifierDeserializer.class)
            @ArraySchema(schema = @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "角色主键，十进制字符串"), minItems = 0)
            List<String> roleIds) {
    }

    /** 契约 API-008 请求体：{@code {status?, resetPassword?, version}}，两字段互斥，version 必填。 */
    public record UpdateRequest(RecordStatus status, String resetPassword, Long version) {
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
