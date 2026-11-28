package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.OwnerBindingDirectory;
import com.huoling.wms.masterdata.identity.domain.PermissionRepository;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import com.huoling.wms.masterdata.identity.domain.RoleScopeRepository;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 登录、续期与登出。令牌只承载身份，权限每次按账号与端解析（19）。
 *
 * <p>本次变更加一道「所属货主是否可用」的判定（契约 1.7）：账号停用与货主停用都按
 * {@code AUTH_1003}（账号不可用）处置，不新增错误码。登录与续期<b>都要判</b>——
 * 只在登录口判的话，旧刷新令牌能一路换出新访问令牌，停用等于没生效。
 */
@Service
public class AuthService {

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleScopeRepository roleScopeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final RefreshTokenStore refreshTokenStore;
    private final OwnerBindingDirectory owners;

    public AuthService(AccountRepository accountRepository,
                       RoleRepository roleRepository,
                       PermissionRepository permissionRepository,
                       RoleScopeRepository roleScopeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService tokenService,
                       RefreshTokenStore refreshTokenStore,
                       OwnerBindingDirectory owners) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.roleScopeRepository = roleScopeRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenStore = refreshTokenStore;
        this.owners = owners;
    }

    public LoginResult login(String accountName, String password) {
        Account account = accountRepository.findByAccountName(accountName)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.AUTH_BAD_CREDENTIALS,
                "账号或密码错误"));
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            throw new IdentityException(IdentityErrorCodes.AUTH_BAD_CREDENTIALS, "账号或密码错误");
        }
        if (!account.enabled()) {
            throw new IdentityException(IdentityErrorCodes.AUTH_ACCOUNT_DISABLED, "账号已停用");
        }
        assertOwnerUsable(account);
        return issue(account);
    }

    public LoginResult refresh(String refreshToken) {
        Long accountId = refreshTokenStore.consume(refreshToken)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.AUTH_REFRESH_INVALID,
                "刷新令牌无效或已使用"));
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.AUTH_REFRESH_INVALID,
                "刷新令牌无效或已使用"));
        if (!account.enabled()) {
            throw new IdentityException(IdentityErrorCodes.AUTH_ACCOUNT_DISABLED, "账号已停用");
        }
        assertOwnerUsable(account);
        return issue(account);
    }

    /**
     * 货主停用即账号不可用（spec「货主停用后拒绝登录」）。
     *
     * <p>只认 {@code DISABLED} 一个条件：货主档案缺失（存量未补录）不拦登录——
     * 把补录进度变成登录故障，等于让一次数据迁移决定现场能不能干活。
     * 数据保留在库、存量单据与任务照常走完，停用不清账号、不清档案、不撤角色。
     */
    private void assertOwnerUsable(Account account) {
        if (account.ownerId() == null) {
            return;
        }
        boolean disabled = owners.find(account.ownerId())
            .map(binding -> !binding.active())
            .orElse(false);
        if (disabled) {
            throw new IdentityException(IdentityErrorCodes.AUTH_ACCOUNT_DISABLED,
                "账号所属货主已停用，请联系运营管理员");
        }
    }

    public void logout(Long accountId, String jti, long remainingSeconds) {
        refreshTokenStore.revokeAll(accountId);
        refreshTokenStore.blacklist(jti, remainingSeconds);
    }

    /**
     * 当前账号在该端的身份：角色与权限点只取令牌端的那一份（{@code 05}「不跨端合并」）。
     *
     * <p>{@code client} 由令牌给出而不是由账号派生：同一个账号在不同端的令牌应当只看到该端的角色，
     * 用账号端派生会让「持哪个端的令牌」与「看哪端的角色」分叉。
     */
    public AccountProfile profile(Long accountId, ClientType client) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "账号不存在"));
        List<Role> roles = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(accountId))
            .stream().filter(role -> role.belongsTo(client) && role.enabled()).toList();
        Set<String> permissions = permissionRepository.findCodesByAccount(accountId, client);
        List<com.huoling.wms.masterdata.identity.domain.RoleScope> scopes = roles.stream()
            .flatMap(role -> roleScopeRepository.findByRole(role.id()).stream())
            .toList();
        com.huoling.wms.masterdata.identity.domain.DataScope dataScope =
            com.huoling.wms.masterdata.identity.domain.DataScopeMerger.merge(scopes);
        return new AccountProfile(account.id(), account.accountName(), roles.stream()
            .map(Role::roleCode).toList(), permissions, dataScope);
    }

    /**
     * 签发令牌：端标识取<b>账号所在端</b>（{@link Account#client()}），角色与权限点按该端解析。
     *
     * <p>写死 {@code HUB} 会把货主端账号开成「有角色却登不进来」：角色挂在 {@code OWNER} 端，
     * 按 {@code HUB} 过滤后一个都不剩，登录直接 {@code AUTHZ_2001}。
     * 反过来，若给货主端账号签发运营端令牌，它就能拿着运营端身份过三端过滤器——
     * 端点与角色的端标识必须同源，这条和 {@code applyRoles} 是同一条规则的两端。
     */
    private LoginResult issue(Account account) {
        ClientType client = account.client();
        List<Role> roles = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(account.id()))
            .stream().filter(role -> role.belongsTo(client) && role.enabled()).toList();
        if (roles.isEmpty()) {
            throw new IdentityException(IdentityErrorCodes.AUTHZ_NO_PERMISSION,
                "账号在" + clientLabel(client) + "没有任何角色");
        }
        Set<String> permissions = permissionRepository.findCodesByAccount(account.id(), client);
        String accessToken = tokenService.issue(account.id(), client);
        String refreshToken = refreshTokenStore.issue(account.id());
        return new LoginResult(accessToken, tokenService.accessTokenSeconds(), refreshToken,
            account.id(), account.accountName(),
            roles.stream().map(Role::roleCode).toList(), permissions);
    }

    /** 端的中文名：只用于提示文案，调用方只判错误码。 */
    private static String clientLabel(ClientType client) {
        return switch (client) {
            case HUB -> "运营端";
            case OWNER -> "货主端";
            case SITE -> "单仓端";
            case MOBILE -> "移动作业端";
        };
    }

    public record LoginResult(String accessToken, long expiresIn, String refreshToken,
                              Long accountId, String accountName,
                              List<String> roles, Set<String> permissions) {
    }

    public record AccountProfile(Long id, String accountName, List<String> roles,
                                 Set<String> permissions,
                                 com.huoling.wms.masterdata.identity.domain.DataScope dataScope) {
    }
}
