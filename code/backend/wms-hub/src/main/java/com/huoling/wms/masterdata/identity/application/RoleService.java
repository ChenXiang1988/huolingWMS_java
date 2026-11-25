package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.Permission;
import com.huoling.wms.masterdata.identity.domain.PermissionRepository;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import com.huoling.wms.masterdata.identity.domain.RoleScope;
import com.huoling.wms.masterdata.identity.domain.RoleScopeRepository;
import com.huoling.wms.masterdata.identity.domain.ScopeDimension;
import com.huoling.wms.masterdata.identity.domain.ScopeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色、权限点分配与数据域配置。权限点只能来自代码声明，整体替换重复执行结果一致。
 * 写用例一律带客户端提交的 {@code version}，写入前比对、写入时按版本推进（契约 API-013、API-016）。
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleScopeRepository roleScopeRepository;
    private final AuditService auditService;
    private final PermissionCache permissionCache;

    public RoleService(RoleRepository roleRepository,
                       PermissionRepository permissionRepository,
                       RoleScopeRepository roleScopeRepository,
                       AuditService auditService,
                       PermissionCache permissionCache) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.roleScopeRepository = roleScopeRepository;
        this.auditService = auditService;
        this.permissionCache = permissionCache;
    }

    /** 契约 API-010：`client` 缺省不过滤。 */
    public List<RoleView> list(ClientType client) {
        List<Role> roles = client == null ? roleRepository.findAll() : roleRepository.findByClient(client);
        return roles.stream()
            .map(role -> new RoleView(role.id(), role.roleCode(), role.roleName(),
                role.appliesToClient(), role.status(), role.version(),
                roleRepository.findPermissionCodesByRole(role.id())))
            .toList();
    }

    @Transactional
    public void create(Long operatorId, String roleCode, String roleName, ClientType client) {
        if (roleRepository.findByCode(roleCode).isPresent()) {
            throw new IdentityException(IdentityErrorCodes.ROLE_CLIENT_MISMATCH, "角色编码已存在");
        }
        Role role = Role.create(null, roleCode, roleName, client);
        roleRepository.insert(role);
        auditService.record(operatorId, 0L, "CREATE_ROLE", null, roleCode);
        permissionCache.bumpVersion();
    }

    /**
     * 契约 API-012：改角色名称与状态，请求体带角色当前 {@code version}。
     * 版本比对先做前置判定，再由 {@code update ... where version = ?} 兜底抢行锁；
     * 落后方返回 COMMON_9004，成功后角色 version 由库侧加一（不由应用回写）。
     */
    @Transactional
    public void update(Long operatorId, Long roleId, String roleName, RecordStatus status,
                       long expectedVersion) {
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ROLE_NOT_FOUND, "角色不存在"));
        role.assertVersion(expectedVersion);
        role.rename(roleName);
        if (status == RecordStatus.DISABLED) {
            role.disable();
        } else {
            role.enable();
        }
        // 先推进版本号抢行锁：并发下同一 version 只有一个请求能写成功，落后方 COMMON_9004。
        if (!roleRepository.advanceVersion(roleId, expectedVersion)) {
            throw new IdentityException(IdentityErrorCodes.COMMON_VERSION_CONFLICT,
                "角色已被修改，请刷新后重提");
        }
        roleRepository.updateProfile(roleId, role.roleName(), role.status());
        auditService.record(operatorId, 0L, "UPDATE_ROLE", String.valueOf(roleId),
            "{\"roleName\":\"" + role.roleName() + "\",\"status\":\"" + role.status() + "\"}");
        permissionCache.bumpVersion();
    }

    @Transactional
    public void assignPermissions(Long operatorId, Long roleId, List<String> permissionCodes,
                                  long expectedVersion) {
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ROLE_NOT_FOUND, "角色不存在"));
        role.assertVersion(expectedVersion);
        List<String> codes = permissionCodes == null ? List.of() : permissionCodes;
        for (String code : codes) {
            Permission permission = permissionRepository.findByCode(code)
                .orElseThrow(() -> new IdentityException(IdentityErrorCodes.PERM_UNKNOWN,
                    "权限点 " + code + " 不存在"));
            if (!permission.enabled()) {
                throw new IdentityException(IdentityErrorCodes.PERM_UNKNOWN,
                    "权限点 " + code + " 已停用");
            }
        }
        // 先推进版本号抢行锁：并发下同一 version 只有一个请求能写成功，落后方 COMMON_9004。
        if (!roleRepository.advanceVersion(roleId, expectedVersion)) {
            throw new IdentityException(IdentityErrorCodes.COMMON_VERSION_CONFLICT,
                "角色已被修改，请刷新后重提");
        }
        List<String> before = roleRepository.findPermissionCodesByRole(roleId);
        roleRepository.replaceRolePermissions(roleId, codes);
        auditService.record(operatorId, 0L, "ASSIGN_PERMISSIONS",
            before.toString(), codes.toString());
        permissionCache.bumpVersion();
    }

    public List<PermissionGroup> permissions() {
        Map<String, List<PermissionItem>> grouped = new LinkedHashMap<>();
        for (Permission permission : permissionRepository.findAllEnabled()) {
            grouped.computeIfAbsent(permission.module(), key -> new java.util.ArrayList<>())
                .add(new PermissionItem(permission.permCode(),
                    permission.permCode().substring(permission.permCode().lastIndexOf('.') + 1)));
        }
        return grouped.entrySet().stream()
            .map(entry -> new PermissionGroup(entry.getKey(), entry.getKey(), entry.getValue()))
            .toList();
    }

    public List<ScopeView> scopes(Long roleId) {
        return roleScopeRepository.findByRole(roleId).stream()
            .map(scope -> new ScopeView(scope.dimension(), scope.scopeType(),
                List.copyOf(scope.targetIds())))
            .toList();
    }

    @Transactional
    public void replaceScopes(Long operatorId, Long roleId, List<ScopeInput> inputs,
                              long expectedVersion) {
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ROLE_NOT_FOUND, "角色不存在"));
        role.assertVersion(expectedVersion);
        List<RoleScope> scopes = (inputs == null ? List.<ScopeInput>of() : inputs).stream()
            .map(input -> RoleScope.define(null, roleId,
                ScopeDimension.parse(input.dimension()),
                ScopeType.parse(input.scopeType()),
                new LinkedHashSet<>(input.targetIds() == null ? Set.of() : input.targetIds())))
            .toList();
        if (!roleRepository.advanceVersion(roleId, expectedVersion)) {
            throw new IdentityException(IdentityErrorCodes.COMMON_VERSION_CONFLICT,
                "角色已被修改，请刷新后重提");
        }
        roleScopeRepository.replaceAll(roleId, scopes);
        auditService.record(operatorId, 0L, "REPLACE_SCOPES", null, "role=" + roleId);
        permissionCache.bumpVersion();
    }

    public record RoleView(Long id, String roleCode, String roleName, ClientType client,
                           RecordStatus status, long version, List<String> permissionCodes) {
    }

    public record PermissionItem(String code, String name) {
    }

    public record PermissionGroup(String module, String moduleName, List<PermissionItem> items) {
    }

    public record ScopeView(ScopeDimension dimension, ScopeType scopeType, List<Long> targetIds) {
    }

    /** 请求体条目：两个枚举字段在入口按闭集校验，闭集外分别返回 SCOPE_6001 与 COMMON_9001。 */
    public record ScopeInput(String dimension, String scopeType, List<Long> targetIds) {
    }
}
