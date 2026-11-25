package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.AuthorizationRules;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账号写入用例。多条不变量在这里收口：重名、员工重复、不能自授、至少保留一个权限管理员。
 */
@Service
public class AccountCommandService {

    private static final String ADMIN_ROLE_CODE = "PERMISSION_ADMIN";

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final PermissionCache permissionCache;

    public AccountCommandService(AccountRepository accountRepository,
                                 RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder,
                                 AuditService auditService,
                                 PermissionCache permissionCache) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.permissionCache = permissionCache;
    }

    @Transactional
    public void create(Long operatorId, CreateCommand command) {
        if (accountRepository.findByAccountName(command.accountName()).isPresent()) {
            throw new IdentityException(IdentityErrorCodes.ACCOUNT_NAME_TAKEN, "账号名已存在");
        }
        if (command.employeeId() != null && accountRepository.existsByEmployeeId(command.employeeId())) {
            throw new IdentityException(IdentityErrorCodes.ACCOUNT_EMPLOYEE_TAKEN, "该员工已有账号");
        }
        Account account = Account.create(null, command.accountName(),
            passwordEncoder.encode(command.initialPassword()), command.employeeId(), null);
        accountRepository.insert(account);
        Account saved = accountRepository.findByAccountName(command.accountName()).orElseThrow();
        if (command.roleIds() != null && !command.roleIds().isEmpty()) {
            applyRoles(operatorId, saved, command.roleIds());
        }
        auditService.record(operatorId, saved.id(), "CREATE_ACCOUNT", null,
            "{\"accountName\":\"" + saved.accountName() + "\"}");
        permissionCache.bumpVersion();
    }

    /**
     * 契约 API-008：停用或重置密码，请求体带账号当前 {@code version}。
     * 版本比对先做前置判定，再由 {@code update ... where version = ?} 兜底抢行锁；
     * 落后方返回 COMMON_9004，成功后账号 version 由库侧加一（不由应用回写）。
     */
    @Transactional
    public void update(Long operatorId, Long accountId, UpdateCommand command) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "账号不存在"));
        account.assertVersion(command.version());
        String before = "{\"status\":\"" + account.status() + "\"}";
        if (command.status() == RecordStatus.DISABLED) {
            assertAdminRemainsAfterDisable(account);
            account.disable();
        } else if (command.status() == RecordStatus.ENABLED) {
            account.enable();
        }
        if (command.resetPassword() != null) {
            account.resetPassword(passwordEncoder.encode(command.resetPassword()));
        }
        // 先推进版本号抢行锁：并发下同一 version 只有一个请求能写成功，落后方 COMMON_9004。
        if (!accountRepository.advanceVersion(accountId, command.version())) {
            throw new IdentityException(IdentityErrorCodes.COMMON_VERSION_CONFLICT,
                "账号已被修改，请刷新后重提");
        }
        accountRepository.updateProfile(accountId, account.status(), account.passwordHash());
        auditService.record(operatorId, accountId, "UPDATE_ACCOUNT", before,
            "{\"status\":\"" + account.status() + "\"}");
        permissionCache.bumpVersion();
    }

    /**
     * 契约 API-009：整体替换账号角色，写入前比对客户端提交的 {@code version}。
     * 版本不一致或并发下被他人推进，返回 COMMON_9004。
     */
    @Transactional
    public void assignRoles(Long operatorId, Long accountId, List<Long> roleIds, long expectedVersion) {
        AuthorizationRules.assertNotSelf(operatorId, accountId);
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "账号不存在"));
        account.assertVersion(expectedVersion);
        // 先推进版本号抢行锁：并发下同一 version 只有一个请求能写成功，落后方 COMMON_9004。
        if (!accountRepository.advanceVersion(accountId, expectedVersion)) {
            throw new IdentityException(IdentityErrorCodes.COMMON_VERSION_CONFLICT,
                "账号已被修改，请刷新后重提");
        }
        List<String> before = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(accountId))
            .stream().map(Role::roleCode).toList();
        applyRoles(operatorId, account, roleIds);
        auditService.record(operatorId, accountId, "ASSIGN_ROLES", before.toString(),
            roleRepository.findByIds(roleIds == null ? List.of() : roleIds).stream()
                .map(Role::roleCode).toList().toString());
        permissionCache.bumpVersion();
    }

    private void applyRoles(Long operatorId, Account account, List<Long> roleIds) {
        List<Role> roles = roleRepository.findByIds(roleIds);
        if (roles.size() != roleIds.size()) {
            throw new IdentityException(IdentityErrorCodes.ROLE_NOT_FOUND, "角色不存在");
        }
        roles.forEach(role -> AuthorizationRules.assertClientMatches(role.appliesToClient(), ClientType.HUB));
        assertAdminRemains(account, roles.stream().anyMatch(r -> ADMIN_ROLE_CODE.equals(r.roleCode())));
        roleRepository.replaceAccountRoles(account.id(), roleIds);
    }

    private void assertAdminRemainsAfterDisable(Account account) {
        boolean isAdmin = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(account.id()))
            .stream().anyMatch(role -> ADMIN_ROLE_CODE.equals(role.roleCode()));
        if (!isAdmin) {
            return;
        }
        long remaining = accountRepository.countEnabledByRoleCode(ADMIN_ROLE_CODE);
        AuthorizationRules.assertPermissionAdminRemains(remaining - 1);
    }

    private void assertAdminRemains(Account account, boolean keepsAdminRole) {
        boolean wasAdmin = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(account.id()))
            .stream().anyMatch(role -> ADMIN_ROLE_CODE.equals(role.roleCode()));
        if (!wasAdmin || keepsAdminRole) {
            return;
        }
        long remaining = accountRepository.countEnabledByRoleCode(ADMIN_ROLE_CODE);
        AuthorizationRules.assertPermissionAdminRemains(remaining - 1);
    }

    public record CreateCommand(String accountName, Long employeeId,
                                String initialPassword, List<Long> roleIds) {
    }

    /** 契约 API-008 的执行指令：两个写字段互斥由入口保证，{@code version} 必填。 */
    public record UpdateCommand(RecordStatus status, String resetPassword, long version) {
    }
}
