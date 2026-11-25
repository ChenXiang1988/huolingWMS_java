package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账号查询。数据域校验在拦截器完成，这里只返回本账号可见范围（运营端首期为全量）。
 */
@Service
public class AccountQueryService {

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;

    public AccountQueryService(AccountRepository accountRepository, RoleRepository roleRepository) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
    }

    public PageResult list(String keyword, RecordStatus status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<AccountView> items = accountRepository.page(keyword, status, safePage, safeSize)
            .stream().map(this::toView).toList();
        long total = accountRepository.count(keyword, status);
        return new PageResult(items, total, safePage, safeSize);
    }

    public AccountView detail(Long id) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "账号不存在"));
        return toView(account);
    }

    private AccountView toView(Account account) {
        List<String> roles = roleRepository.findByIds(roleRepository.findRoleIdsByAccount(account.id()))
            .stream().map(Role::roleCode).toList();
        return new AccountView(account.id(), account.accountName(), account.employeeId(),
            account.status(), roles, account.version());
    }

    public record AccountView(Long id, String accountName, Long employeeId,
                              RecordStatus status, List<String> roles, long version) {
    }

    public record PageResult(List<AccountView> items, long total, int page, int size) {
    }
}
