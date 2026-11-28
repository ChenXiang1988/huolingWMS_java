package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.DataScope;
import com.huoling.wms.masterdata.identity.domain.DataScopeMerger;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import com.huoling.wms.masterdata.identity.domain.RoleScope;
import com.huoling.wms.masterdata.identity.domain.RoleScopeRepository;
import com.huoling.wms.masterdata.limit.ApiRateLimiter;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 货主档案的数据域解析（功能权限由统一鉴权切面校验，数据域在这里定，再由仓储层注入）。
 *
 * <p>四道门的第三道：接口一旦带域标识参数，数据域立即生效（`19`）。
 * 三处判定写死在这里，与 {@link OwnerScope} 同一口径：
 * <ul>
 *   <li>端标识不是 {@code HUB} 一律 {@code FORBIDDEN_OWNER}：货主端与单仓端令牌不进入业务；</li>
 *   <li>域为空直接拒绝，不回落成「全部」（OI-08、`05` 第 3 节）；</li>
 *   <li>建档要求全域，由 {@link #requireAllForCreate(OwnerScope)} 判定。</li>
 * </ul>
 *
 * <p>每次拒绝都记一次 {@code owner_scope_denied_total} 并留一条日志：越权被拒是安全事件，
 * 不该只在响应码里留痕（tasks 1.18）。
 */
@Component
public class OwnerAccessGuard {

    private final RoleRepository roleRepository;
    private final RoleScopeRepository roleScopeRepository;
    private final ApiRateLimiter rateLimiter;
    private final OwnerMetrics metrics;

    public OwnerAccessGuard(RoleRepository roleRepository,
                            RoleScopeRepository roleScopeRepository,
                            ApiRateLimiter rateLimiter,
                            OwnerMetrics metrics) {
        this.roleRepository = roleRepository;
        this.roleScopeRepository = roleScopeRepository;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /**
     * 解析本次请求的货主域。
     *
     * @throws OwnerContractException 端标识不是 HUB 或货主域为空时 {@code FORBIDDEN_OWNER}
     */
    public OwnerScope requireHubScope(Long accountId, ClientType client) {
        if (client != ClientType.HUB) {
            metrics.scopeDenied("client_not_hub", "accountId=" + accountId + " client=" + client);
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER,
                "该接口只接受运营端令牌");
        }
        List<Long> roleIds = roleRepository.findRoleIdsByAccount(accountId);
        List<Role> roles = roleRepository.findByIds(roleIds).stream()
            .filter(role -> role.belongsTo(ClientType.HUB) && role.enabled())
            .toList();
        List<RoleScope> scopes = roles.stream()
            .flatMap(role -> roleScopeRepository.findByRole(role.id()).stream())
            .toList();
        DataScope dataScope;
        try {
            dataScope = DataScopeMerger.merge(scopes);
        } catch (IdentityException e) {
            metrics.scopeDenied("empty_scope", "accountId=" + accountId);
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER,
                "货主数据域为空，拒绝访问");
        }
        OwnerScope scope = new OwnerScope(dataScope.allOwners(), dataScope.ownerIds());
        if (scope.empty()) {
            metrics.scopeDenied("empty_scope", "accountId=" + accountId);
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER,
                "货主数据域为空，拒绝访问");
        }
        return scope;
    }

    /**
     * 建档要求全域：新建对象无法落入既有指定列表（OI-08）。
     *
     * @throws OwnerContractException 指定列表账号建档时 {@code FORBIDDEN_OWNER}
     */
    public void requireAllForCreate(OwnerScope scope) {
        try {
            scope.requireAll();
        } catch (OwnerContractException e) {
            metrics.scopeDenied("not_all_owners", "ownerIds=" + scope.ownerIds().size());
            throw e;
        }
    }

    /** 运营端限流：与商品档案同一个端池，按接口维度计数（契约 1.1「限流与熔断」）。 */
    public void limitHub(String apiCode) {
        rateLimiter.checkHub(apiCode);
    }
}
