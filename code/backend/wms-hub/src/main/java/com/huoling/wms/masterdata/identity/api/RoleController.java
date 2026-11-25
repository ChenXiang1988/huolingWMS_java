package com.huoling.wms.masterdata.identity.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.huoling.wms.masterdata.identity.application.AcceptanceService;
import com.huoling.wms.masterdata.identity.application.RoleService;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.domain.ScopeDimension;
import com.huoling.wms.masterdata.identity.domain.ScopeType;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * 角色、权限点与数据域接口（API-010 至 API-016）。
 * 写接口的枚举闭集、必填字段与幂等键一律在入口校验，不落库、不出 5xx（契约「入口拒绝」）。
 *
 * <p>标识在边界转换（契约「标识、类型与枚举约定」）：角色 {@code id} 与数据域
 * {@code targetIds[]} 出参为十进制字符串，入参同样只收字符串，数字形态按 {@code COMMON_9001} 拒绝。
 */
@RestController
@RequestMapping("/hub/api")
public class RoleController {

    private final RoleService roleService;
    private final AcceptanceService acceptanceService;
    private final JwtTokenService tokenService;

    public RoleController(RoleService roleService,
                          AcceptanceService acceptanceService,
                          JwtTokenService tokenService) {
        this.roleService = roleService;
        this.acceptanceService = acceptanceService;
        this.tokenService = tokenService;
    }

    @GetMapping("/roles")
    @RequiresPermission("hub.role.read")
    public ApiResponse<List<RoleResponse>> list(
            @RequestParam(required = false) ClientType client) {
        return ApiResponse.ok(roleService.list(client).stream().map(RoleController::toResponse).toList(),
            traceId());
    }

    @PostMapping("/roles")
    @RequiresPermission("hub.role.create")
    public ResponseEntity<ApiResponse<AccountController.AcceptTicket>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody CreateRoleRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        requireText(request.roleCode(), "roleCode");
        requireText(request.roleName(), "roleName");
        if (request.client() == null) {
            throw badRequest("字段 client 必填，只接受 HUB、OWNER、SITE、MOBILE");
        }
        String acceptNo = acceptanceService.submit(user.accountId(), "API-011", "/hub/api/roles",
            idempotencyKey, request.toString(),
            () -> roleService.create(user.accountId(), request.roleCode(), request.roleName(), request.client()));
        return accepted(acceptNo);
    }

    @PatchMapping("/roles/{id}")
    @RequiresPermission("hub.role.update")
    public ResponseEntity<ApiResponse<AccountController.AcceptTicket>> update(
            @Parameter(description = "角色主键，十进制字符串",
                schema = @Schema(type = "string", pattern = Identifiers.PATTERN))
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody UpdateRoleRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        long roleId = Identifiers.parse(id, "id");
        // 契约 API-012 请求体 {roleName, status, version} 三项皆必填，缺一即入口拒绝。
        requireText(request.roleName(), "roleName");
        if (request.status() == null) {
            throw badRequest("字段 status 必填，只接受 ENABLED 与 DISABLED");
        }
        requireVersion(request.version());
        String acceptNo = acceptanceService.submit(user.accountId(), "API-012", "/hub/api/roles/" + id,
            idempotencyKey, request.toString(),
            () -> roleService.update(user.accountId(), roleId, request.roleName(), request.status(),
                request.version()));
        return accepted(acceptNo);
    }

    @PutMapping("/roles/{id}/permissions")
    @RequiresPermission("hub.role.assign")
    public ResponseEntity<ApiResponse<AccountController.AcceptTicket>> assignPermissions(
            @Parameter(description = "角色主键，十进制字符串",
                schema = @Schema(type = "string", pattern = Identifiers.PATTERN))
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AssignPermissionsRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        long roleId = Identifiers.parse(id, "id");
        requireVersion(request.version());
        String acceptNo = acceptanceService.submit(user.accountId(), "API-013",
            "/hub/api/roles/" + id + "/permissions", idempotencyKey, request.toString(),
            () -> roleService.assignPermissions(user.accountId(), roleId, request.permissionCodes(),
                request.version()));
        return accepted(acceptNo);
    }

    @GetMapping("/permissions")
    @RequiresPermission("hub.role.read")
    public ApiResponse<List<RoleService.PermissionGroup>> permissions() {
        return ApiResponse.ok(roleService.permissions(), traceId());
    }

    @GetMapping("/roles/{id}/scopes")
    @RequiresPermission("hub.role.scope")
    public ApiResponse<List<ScopeResponse>> scopes(
            @Parameter(description = "角色主键，十进制字符串",
                schema = @Schema(type = "string", pattern = Identifiers.PATTERN))
            @PathVariable String id) {
        long roleId = Identifiers.parse(id, "id");
        return ApiResponse.ok(roleService.scopes(roleId).stream().map(RoleController::toResponse).toList(),
            traceId());
    }

    @PutMapping("/roles/{id}/scopes")
    @RequiresPermission("hub.role.scope")
    public ResponseEntity<ApiResponse<AccountController.AcceptTicket>> replaceScopes(
            @Parameter(description = "角色主键，十进制字符串",
                schema = @Schema(type = "string", pattern = Identifiers.PATTERN))
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ReplaceScopesRequest request) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        long roleId = Identifiers.parse(id, "id");
        requireVersion(request.version());
        List<RoleService.ScopeInput> scopes = validateScopes(request.scopes());
        String acceptNo = acceptanceService.submit(user.accountId(), "API-016",
            "/hub/api/roles/" + id + "/scopes", idempotencyKey,
            request.toString(),
            () -> roleService.replaceScopes(user.accountId(), roleId, scopes, request.version()));
        return accepted(acceptNo);
    }

    /**
     * 数据域枚举闭集在入口判定：{@code dimension} 闭集外返回 SCOPE_6001，
     * {@code scopeType} 闭集外返回 COMMON_9001，都不进入受理环（契约 API-016）。
     * {@code targetIds[]} 只接受十进制字符串，数字形态在这里按 COMMON_9001 拒绝。
     */
    private List<RoleService.ScopeInput> validateScopes(List<ScopeRequest> scopes) {
        if (scopes == null) {
            return List.of();
        }
        return scopes.stream().map(scope -> {
            if (scope.dimension() == null) {
                throw badRequest("字段 scopes[].dimension 必填，只接受 OWNER 与 WAREHOUSE");
            }
            if (scope.scopeType() == null) {
                throw badRequest("字段 scopes[].scopeType 必填，只接受 ALL 与 LIST");
            }
            ScopeDimension dimension = ScopeDimension.parse(scope.dimension());
            ScopeType scopeType = ScopeType.parse(scope.scopeType());
            return new RoleService.ScopeInput(dimension.name(), scopeType.name(),
                Identifiers.parseAll(scope.targetIds(), "scopes[].targetIds"));
        }).toList();
    }

    private void requireVersion(Long version) {
        if (version == null) {
            throw badRequest("字段 version 必填");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest("字段 " + field + " 必填");
        }
    }

    private IdentityException badRequest(String message) {
        return new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, message);
    }

    private ResponseEntity<ApiResponse<AccountController.AcceptTicket>> accepted(String acceptNo) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.ok(new AccountController.AcceptTicket(acceptNo), traceId()));
    }

    private String traceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 契约 API-010 的角色条目：{@code id} 为十进制字符串。 */
    public record RoleResponse(
            @Schema(type = "string", pattern = Identifiers.PATTERN, description = "角色主键，十进制字符串")
            String id,
            String roleCode,
            String roleName,
            ClientType client,
            RecordStatus status,
            long version,
            List<String> permissionCodes) {
    }

    /** 契约 API-015 的数据域条目：{@code targetIds[]} 为十进制字符串数组。 */
    public record ScopeResponse(
            ScopeDimension dimension,
            ScopeType scopeType,
            @ArraySchema(schema = @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "数据域目标标识，十进制字符串"))
            List<String> targetIds) {
    }

    private static RoleResponse toResponse(RoleService.RoleView view) {
        return new RoleResponse(Identifiers.toWire(view.id()), view.roleCode(), view.roleName(),
            view.client(), view.status(), view.version(), view.permissionCodes());
    }

    private static ScopeResponse toResponse(RoleService.ScopeView view) {
        return new ScopeResponse(view.dimension(), view.scopeType(),
            Identifiers.toWire(view.targetIds()));
    }

    public record CreateRoleRequest(String roleCode, String roleName, ClientType client) {
    }

    /** 契约 API-012 请求体：{@code {roleName, status, version}}，三项皆必填，version 参与乐观锁。 */
    public record UpdateRoleRequest(String roleName, RecordStatus status, Long version) {
    }

    public record AssignPermissionsRequest(List<String> permissionCodes, Long version) {
    }

    public record ReplaceScopesRequest(List<ScopeRequest> scopes, Long version) {
    }

    public record ScopeRequest(
            @Schema(allowableValues = {"OWNER", "WAREHOUSE"},
                description = "数据域维度，闭集外返回 SCOPE_6001（枚举 E-14）")
            String dimension,
            @Schema(allowableValues = {"ALL", "LIST"},
                description = "数据域范围类型，闭集外返回 COMMON_9001（枚举 E-13）")
            String scopeType,
            @JsonDeserialize(contentUsing = IdentifierDeserializer.class)
            @ArraySchema(schema = @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "数据域目标标识，十进制字符串；传 JSON 数字返回 COMMON_9001"))
            List<String> targetIds) {
    }
}
