package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
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

    public AuthService(AccountRepository accountRepository,
                       RoleRepository roleRepository,
                       PermissionRepository permissionRepository,
                       RoleScopeRepository roleScopeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService tokenService,
                       RefreshTokenStore refreshTokenStore) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.roleScopeRepository = roleScopeRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenStore = refreshTokenStore;
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
        return issue(account);
    }

    public void logout(Long accountId, String jti, long remainingSeconds) {
        refreshTokenStore.revokeAll(accountId);
        refreshTokenStore.blacklist(jti, remainingSeconds);
    }

    public AccountProfile profile(Long accountId) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "账号不存在"));
        List<Role> roles = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(accountId))
            .stream().filter(role -> role.belongsTo(ClientType.HUB) && role.enabled()).toList();
        Set<String> permissions = permissionRepository.findCodesByAccount(accountId, ClientType.HUB);
        List<com.huoling.wms.masterdata.identity.domain.RoleScope> scopes = roles.stream()
            .flatMap(role -> roleScopeRepository.findByRole(role.id()).stream())
            .toList();
        com.huoling.wms.masterdata.identity.domain.DataScope dataScope =
            com.huoling.wms.masterdata.identity.domain.DataScopeMerger.merge(scopes);
        return new AccountProfile(account.id(), account.accountName(), roles.stream()
            .map(Role::roleCode).toList(), permissions, dataScope);
    }

    private LoginResult issue(Account account) {
        List<Role> roles = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(account.id()))
            .stream().filter(role -> role.belongsTo(ClientType.HUB) && role.enabled()).toList();
        if (roles.isEmpty()) {
            throw new IdentityException(IdentityErrorCodes.AUTHZ_NO_PERMISSION, "账号在运营端没有任何角色");
        }
        Set<String> permissions = permissionRepository.findCodesByAccount(account.id(), ClientType.HUB);
        String accessToken = tokenService.issue(account.id(), ClientType.HUB);
        String refreshToken = refreshTokenStore.issue(account.id());
        return new LoginResult(accessToken, tokenService.accessTokenSeconds(), refreshToken,
            account.id(), account.accountName(),
            roles.stream().map(Role::roleCode).toList(), permissions);
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
