package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.DataScope;
import com.huoling.wms.masterdata.identity.domain.DataScopeMerger;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import com.huoling.wms.masterdata.identity.domain.RoleScope;
import com.huoling.wms.masterdata.identity.domain.RoleScopeRepository;
import com.huoling.wms.masterdata.limit.ApiRateLimiter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据域解析（INV-19 的第二层：功能权限由统一鉴权切面校验，数据域在这里定，再由仓储层注入）。
 *
 * <p>货主端：归属货主取登录账号绑定的 {@code owner_id}，取不到一律 {@code FORBIDDEN_OWNER}，
 * 不回落成「全量」。单仓端：本仓取账号的仓数据域，必须是唯一一仓，否则 {@code FORBIDDEN_SITE}。
 * 两者都不做内部货主分支（D03）。
 */
@Component
public class ArchiveAccessGuard {

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final RoleScopeRepository roleScopeRepository;
    private final ApiRateLimiter rateLimiter;

    public ArchiveAccessGuard(AccountRepository accountRepository,
                              RoleRepository roleRepository,
                              RoleScopeRepository roleScopeRepository,
                              ApiRateLimiter rateLimiter) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.roleScopeRepository = roleScopeRepository;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 货主端数据域：返回登录账号绑定的归属货主。
     *
     * @throws ArchiveException 非货主端令牌或账号未绑定货主时 {@code FORBIDDEN_OWNER}
     */
    public Long requireOwner(Long accountId, ClientType client) {
        if (client != ClientType.OWNER) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "该接口只接受货主端令牌");
        }
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "账号不存在"));
        if (account.ownerId() == null) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "账号未绑定货主，拒绝访问");
        }
        return account.ownerId();
    }

    /**
     * 单仓端数据域：返回本仓标识与合并后的数据域（用于校验档案归属是否在可见范围内）。
     *
     * @throws ArchiveException 非单仓端令牌、无仓数据域或仓域不唯一时 {@code FORBIDDEN_SITE}
     */
    public SiteScope requireSite(Long accountId, ClientType client) {
        if (client != ClientType.SITE) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE, "该接口只接受单仓端令牌");
        }
        DataScope dataScope = dataScopeOf(accountId, ClientType.SITE);
        if (dataScope.allWarehouses() || dataScope.warehouseIds().size() != 1) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE,
                "单仓端必须绑定唯一本仓数据域，拒绝访问");
        }
        return new SiteScope(dataScope.warehouseIds().iterator().next(), dataScope);
    }

    /**
     * 运营端货主数据域（API-020 货主选择器）：域为「全部」返回全量，域为「指定列表」只看域内。
     *
     * <p>与 {@code masterdata.owner} 的 {@code OwnerAccessGuard} 同一口径，两边都委托
     * {@link DataScopeMerger}：合并规则、未配置维度按全部、指定列表为空即空域这三条只有一处实现。
     * 这里单独提供运营端入口而不是复用对方，是因为架构断言要求两个业务包双向不得依赖（AR-11），
     * 而选择器按设计落在档案包（design「模块与依赖」的「选择器改造」一行）。
     *
     * @throws ArchiveException 端标识不是 HUB 或货主域为空时 {@code FORBIDDEN_OWNER}（不回落成全量）
     */
    public DataScope requireHubOwnerScope(Long accountId, ClientType client) {
        if (client != ClientType.HUB) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "该接口只接受运营端令牌");
        }
        return dataScopeOf(accountId, ClientType.HUB);
    }

    /** 货主端限流。 */
    public void limitOwner(Long ownerId, String apiCode) {
        rateLimiter.checkOwner(ownerId, apiCode);
    }

    /** 运营端限流。 */
    public void limitHub(String apiCode) {
        rateLimiter.checkHub(apiCode);
    }

    /** 单仓端限流。 */
    public void limitSite(Long warehouseId, String apiCode) {
        rateLimiter.checkSite(warehouseId, apiCode);
    }

    /**
     * 按端加载角色并合并数据域。
     *
     * <p>空域按端给码：单仓端是 {@code FORBIDDEN_SITE}，运营端是 {@code FORBIDDEN_OWNER}
     * ——两个端在契约里的越权码不同，这里不做「一个码打天下」的合并。
     */
    private DataScope dataScopeOf(Long accountId, ClientType client) {
        List<Long> roleIds = roleRepository.findRoleIdsByAccount(accountId);
        List<Role> roles = roleRepository.findByIds(roleIds).stream()
            .filter(role -> role.belongsTo(client) && role.enabled())
            .toList();
        List<RoleScope> scopes = roles.stream()
            .flatMap(role -> roleScopeRepository.findByRole(role.id()).stream())
            .toList();
        try {
            return DataScopeMerger.merge(scopes);
        } catch (IdentityException e) {
            if (client == ClientType.HUB) {
                throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER,
                    "货主数据域为空，拒绝访问");
            }
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE, "本仓数据域为空，拒绝访问");
        }
    }

    /** 单仓端会话数据域：本仓标识加合并后的数据域。 */
    public record SiteScope(Long warehouseId, DataScope dataScope) {
    }
}
