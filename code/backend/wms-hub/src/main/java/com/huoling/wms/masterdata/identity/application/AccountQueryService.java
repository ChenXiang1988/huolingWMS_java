package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.OwnerBindingDirectory;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 账号查询。数据域校验在拦截器完成，这里只返回本账号可见范围（运营端首期为全量）。
 *
 * <p>本次变更给响应补四个字段（契约 1.7）：{@code client}、{@code ownerId}、{@code ownerNo}、
 * {@code ownerName}。端标识按「是否绑定货主」推导（{@code ownerId != null} 即货主端），
 * 不引入按员工/货主分型的类型分支（D03）；后两项由货主档案关联取得，
 * 档案缺失时留空——前端显示「—」，不显示裸标识、不做推断。
 */
@Service
public class AccountQueryService {

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final OwnerBindingDirectory owners;

    public AccountQueryService(AccountRepository accountRepository, RoleRepository roleRepository,
                               OwnerBindingDirectory owners) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.owners = owners;
    }

    public PageResult list(String keyword, RecordStatus status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<AccountView> items = accountRepository.page(keyword, status, safePage, safeSize).stream()
            .map(this::toView)
            .toList();
        long total = accountRepository.count(keyword, status);
        return new PageResult(items, total, safePage, safeSize);
    }

    public AccountView detail(Long id) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "账号不存在"));
        return toView(account);
    }

    /**
     * 账号所在端：写入接口在入口判「角色端标识与账号端一致」时取（契约 API-009 的 {@code ROLE_4002}）。
     *
     * <p>只取端标识，不回查角色与货主档案：入口判定只需要这一个字段，
     * 为此走一遍 {@link #detail(Long)} 会多出两次与本判定无关的查询。
     * 判定本身只在 {@link Account#client()} 一处，这里直接委托，不重写「按 {@code ownerId} 是否为空分端」。
     */
    public ClientType clientOf(Long accountId) {
        return accountRepository.findById(accountId)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "账号不存在"))
            .client();
    }

    private AccountView toView(Account account) {
        List<String> roles = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(account.id()))
            .stream().map(Role::roleCode).toList();
        Map<Long, OwnerBindingDirectory.OwnerBinding> bound = ownersOf(List.of(account));
        OwnerBindingDirectory.OwnerBinding owner = account.ownerId() == null
            ? null : bound.get(account.ownerId());
        return new AccountView(account.id(), account.accountName(), account.employeeId(),
            account.client(), account.ownerId(),
            owner == null ? null : owner.ownerNo(), owner == null ? null : owner.ownerName(),
            account.status(), roles, account.version());
    }

    /** 列表页一次性取齐本页涉及的货主，避免逐行回查（N+1）。 */
    private Map<Long, OwnerBindingDirectory.OwnerBinding> ownersOf(List<Account> accounts) {
        List<Long> ownerIds = accounts.stream().map(Account::ownerId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        return owners.find(ownerIds).stream()
            .collect(Collectors.toMap(OwnerBindingDirectory.OwnerBinding::ownerId,
                Function.identity()));
    }

    /**
     * 账号条目（契约 1.7 扩展后的形状）。
     *
     * @param client    端标识；货主端账号为 {@code OWNER}，其余为 {@code HUB}
     * @param ownerId   归属货主标识（十进制字符串由接口层转换），非货主端为空
     * @param ownerNo   归属货主编号，非货主端或档案缺失时为空
     * @param ownerName 归属货主名称，非货主端或档案缺失时为空
     */
    public record AccountView(Long id, String accountName, Long employeeId, ClientType client,
                              Long ownerId, String ownerNo, String ownerName,
                              RecordStatus status, List<String> roles, long version) {
    }

    public record PageResult(List<AccountView> items, long total, int page, int size) {
    }
}
