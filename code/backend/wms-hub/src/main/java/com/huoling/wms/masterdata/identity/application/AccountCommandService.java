package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.AuthorizationRules;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.OwnerBindingDirectory;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 账号写入用例。多条不变量在这里收口：重名、员工重复、不能自授、至少保留一个权限管理员，
 * 以及本次变更新增的两条：开通货主端账号必须绑定存在的货主（OI-10、契约 1.7）、绑定后不可改。
 */
@Service
public class AccountCommandService {

    private static final String ADMIN_ROLE_CODE = "PERMISSION_ADMIN";

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final PermissionCache permissionCache;
    private final OwnerBindingDirectory owners;

    public AccountCommandService(AccountRepository accountRepository,
                                 RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder,
                                 AuditService auditService,
                                 PermissionCache permissionCache,
                                 OwnerBindingDirectory owners) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.permissionCache = permissionCache;
        this.owners = owners;
    }

    @Transactional
    public void create(Long operatorId, CreateCommand command) {
        if (accountRepository.findByAccountName(command.accountName()).isPresent()) {
            throw new IdentityException(IdentityErrorCodes.ACCOUNT_NAME_TAKEN, "账号名已存在");
        }
        if (command.employeeId() != null && accountRepository.existsByEmployeeId(command.employeeId())) {
            throw new IdentityException(IdentityErrorCodes.ACCOUNT_EMPLOYEE_TAKEN, "该员工已有账号");
        }
        assertAccountBinding(command.client(), command.employeeId(), command.ownerId());
        Long ownerId = command.ownerId();
        Account account = Account.create(null, command.accountName(),
            passwordEncoder.encode(command.initialPassword()), command.employeeId(), ownerId);
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
        assertOwnerUnchanged(account, command.ownerId());
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
            roleRepository.findByIds(distinctRoleIds(roleIds)).stream()
                .map(Role::roleCode).toList().toString());
        permissionCache.bumpVersion();
    }

    /**
     * 入口同步校验：货主端的归属货主必须给出且存在（契约 1.7 的 400 口径）。
     *
     * <p>为什么入口也要判一次：账号开通用的是异步受理环（202 + 受理号），只把判定放在受理执行里，
     * 调用方拿到的是 202 与一条事后才失败的受理单；而契约 1.7 明写「400（{@code COMMON_9001}），
     * 不创建账号」。两处都用同一条规则，入口负责「早失败」，受理执行里那一层负责「并发窗口」。
     */
    public void assertAccountBinding(ClientType client, Long employeeId, Long ownerId) {
        requireOwnerBinding(client, ownerId);
        if (client == ClientType.OWNER) {
            if (employeeId != null) {
                throw badRequest("货主端账号不绑员工，不得提供 employeeId");
            }
            return;
        }
        if (employeeId == null) {
            throw badRequest("运营端账号必须绑定员工 employeeId");
        }
    }

    /**
     * 入口同步校验：{@code roleIds} 里每个角色的端标识必须与目标账号所在端一致（契约 1.7、API-009）。
     *
     * <p>两个写入口共用：开通账号（API-006）传的是<b>将要开通</b>的端，整体替换角色（API-009）
     * 传的是目标账号的<b>实际</b>端（{@code AccountQueryService#clientOf}）。两个入口都在提交前判，
     * 因为两侧都是异步受理环（202 + 受理号），只把判定放在受理执行里，调用方拿到的是 202
     * 与一条事后才失败的受理单；而角色端标识不匹配属参数错误，必须在入口按既有
     * {@code ROLE_4002} 直接拒绝，且<b>不建账号、不占受理号</b>。
     *
     * <p>两个方向都要挡：{@code client=OWNER} 传运营端角色、{@code client=HUB} 传货主端角色。
     * 只挡一个方向的话，另一类账号会带着「另一端」的权限登进来——令牌端标识与角色端标识分叉，
     * 权限点按账号粒度合并，等于用货主端令牌拿到运营端权限。
     *
     * <p>角色是否存在先判：角色不存在是 {@code ROLE_4001}（404），与端不符的 {@code ROLE_4002}（400）
     * 是两个不同的原因，不能因为「先比端」把不存在的角色报成端不匹配。
     */
    public void assertAccountRoles(ClientType client, List<Long> roleIds) {
        if (client == null) {
            return;
        }
        // 先按集合语义去重再比数量：同一个标识写两遍只是「同一个角色」，不是两个角色。
        List<Long> distinct = distinctRoleIds(roleIds);
        if (distinct.isEmpty()) {
            return;
        }
        List<Role> roles = roleRepository.findByIds(distinct);
        if (roles.size() != distinct.size()) {
            throw new IdentityException(IdentityErrorCodes.ROLE_NOT_FOUND, "角色不存在");
        }
        roles.forEach(role -> AuthorizationRules.assertClientMatches(role.appliesToClient(), client));
    }

    /**
     * 入口同步校验：已绑定货主的账号不允许改归属货主（契约 1.7 的 400 口径）。
     *
     * <p>与 {@link #assertOwnerBindable} 同理：入口早失败返回 400，受理执行里再判一次防并发。
     */
    public void assertOwnerUnchanged(Long accountId, Long submittedOwnerId) {
        if (submittedOwnerId == null) {
            return;
        }
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "账号不存在"));
        assertOwnerUnchanged(account, submittedOwnerId);
    }

    /**
     * 开通时的货主绑定校验（契约 1.7、spec「货主端账号必须绑定存在的货主」）。
     *
     * <p>三条一起判：货主端必须给 {@code ownerId}、该档案必须存在、运营端不得给 {@code ownerId}。
     * 一律 {@code COMMON_9001}，不创建账号。存在性最终还有库层外键
     * {@code fk_wms_account_owner} 兜底（第二发布窗口），应用层先给出可读原因。
     */
    private Long requireOwnerBinding(ClientType client, Long ownerId) {
        if (client == ClientType.OWNER) {
            if (ownerId == null) {
                throw badRequest("开通货主端账号必须指定归属货主 ownerId");
            }
            if (owners.find(ownerId).isEmpty()) {
                throw badRequest("归属货主不存在，请先在货主档案中建档");
            }
            return ownerId;
        }
        if (ownerId != null) {
            throw badRequest("运营端账号不得指定归属货主 ownerId");
        }
        return null;
    }

    /**
     * 绑定不可变（OI-10、契约 1.7）：已绑定的归属货主不允许改，换货主只能停用账号另开。
     *
     * <p>判「提交值与现值不同」而不是「提交值非空」：只改状态或密码的请求不必回传 {@code ownerId}，
     * 回传同一个值也照常放行；空→值、值→另一值都按 {@code COMMON_9001} 拒绝。
     * 「值→空」在 JSON 里与「未提供」不可区分，因此本入口不提供清空归属货主的路径——
     * 与库层触发器 {@code wms_account_owner_immutable} 的口径一致。
     */
    private void assertOwnerUnchanged(Account account, Long submittedOwnerId) {
        if (submittedOwnerId == null || submittedOwnerId.equals(account.ownerId())) {
            return;
        }
        throw badRequest("归属货主已绑定，不允许变更；换货主请停用该账号后另开新账号");
    }

    private IdentityException badRequest(String message) {
        return new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, message);
    }

    /**
     * 角色落库：端标识按<b>账号实际所在端</b>比对，不用常量。
     *
     * <p>写成 {@code ClientType.HUB} 会有两个方向的错：货主端账号授 {@code OWNER_USER} 被判
     * {@code ROLE_4002}（货主端账号拿不到任何角色，登录后调货主端接口一律 {@code AUTHZ_2001}），
     * 而货主端账号挂运营端角色又不设防（持货主端令牌可过三端过滤器，数据域默认「全部」即越权）。
     * 判定统一走 {@link Account#client()}：绑定货主即货主端，否则运营端。
     *
     * <p>开通与整体替换两条路径的同一判定都已在入口 {@link #assertAccountRoles} 做过一次
     * （早失败返回 400），这里再做一次是防「入口与受理之间账号被改动」的并发窗口。
     */
    private void applyRoles(Long operatorId, Account account, List<Long> roleIds) {
        List<Long> distinct = distinctRoleIds(roleIds);
        List<Role> roles = roleRepository.findByIds(distinct);
        if (roles.size() != distinct.size()) {
            throw new IdentityException(IdentityErrorCodes.ROLE_NOT_FOUND, "角色不存在");
        }
        ClientType accountClient = account.client();
        roles.forEach(role -> AuthorizationRules.assertClientMatches(role.appliesToClient(), accountClient));
        assertAdminRemains(account, roles.stream().anyMatch(r -> ADMIN_ROLE_CODE.equals(r.roleCode())));
        // 落库同样用去重后的集合：整体替换是集合语义，重复标识不该产生第二条绑定。
        roleRepository.replaceAccountRoles(account.id(), distinct);
    }

    /**
     * 角色标识按集合语义去重，保持入参顺序。
     *
     * <p>为什么必须先去重再判：{@code roleIds} 是「整体替换」的集合语义，同一个标识写两遍不代表两个角色。
     * 查库按标识返回（天然去重），拿入参个数去比长度时重复标识会让 {@code size} 对不上，
     * 把一个存在的角色误报成 {@code ROLE_4001}（角色不存在）。
     *
     * <p>空值与空数组同义：契约 API-009 明写「{@code roleIds[]} 可空，空数组表示清空」。
     */
    private static List<Long> distinctRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(roleIds));
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

    /**
     * 契约 API-006 的执行指令。
     *
     * <p>{@code client} 与 {@code ownerId} 是本次变更对既有接口的字段扩展（契约 1.7）：
     * 货主端账号必须带归属货主，运营端账号不得带。
     */
    public record CreateCommand(String accountName, Long employeeId, ClientType client, Long ownerId,
                                String initialPassword, List<Long> roleIds) {
    }

    /**
     * 契约 API-008 的执行指令：两个写字段互斥由入口保证，{@code version} 必填。
     *
     * <p>{@code ownerId} 可空：不传表示不改归属货主；传了且与现值不同即拒绝（绑定不可变）。
     */
    public record UpdateCommand(RecordStatus status, String resetPassword, Long ownerId, long version) {
    }
}
