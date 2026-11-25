package com.huoling.wms.masterdata.identity.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AccountMapper;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AccountPO;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepositoryImpl implements AccountRepository {

    private final AccountMapper mapper;

    public AccountRepositoryImpl(AccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Account> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<Account> findByAccountName(String accountName) {
        AccountPO po = mapper.selectOne(new LambdaQueryWrapper<AccountPO>()
            .eq(AccountPO::getAccountName, accountName));
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public boolean existsByEmployeeId(Long employeeId) {
        return mapper.selectCount(new LambdaQueryWrapper<AccountPO>()
            .eq(AccountPO::getEmployeeId, employeeId)) > 0;
    }

    @Override
    public void insert(Account account) {
        AccountPO po = toPO(account);
        OffsetDateTime now = OffsetDateTime.now();
        po.setId(account.id() == null ? IdWorker.getId() : account.id());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        po.setCreatedBy(0L);
        po.setUpdatedBy(0L);
        mapper.insert(po);
    }

    @Override
    public void updateProfile(Long accountId, RecordStatus status, String passwordHash) {
        mapper.updateProfile(accountId, status.name(), passwordHash);
    }

    @Override
    public List<Account> page(String keyword, RecordStatus status, int page, int size) {
        LambdaQueryWrapper<AccountPO> wrapper = new LambdaQueryWrapper<AccountPO>()
            .like(keyword != null && !keyword.isBlank(), AccountPO::getAccountName, keyword)
            .eq(status != null, AccountPO::getStatus, status == null ? null : status.name())
            .orderByDesc(AccountPO::getCreatedAt);
        return mapper.selectPage(new Page<>(page, size), wrapper).getRecords()
            .stream().map(this::toDomain).toList();
    }

    @Override
    public long count(String keyword, RecordStatus status) {
        return mapper.selectCount(new LambdaQueryWrapper<AccountPO>()
            .like(keyword != null && !keyword.isBlank(), AccountPO::getAccountName, keyword)
            .eq(status != null, AccountPO::getStatus, status == null ? null : status.name()));
    }

    @Override
    public long countEnabledByRoleCode(String roleCode) {
        return mapper.countEnabledByRoleCode(roleCode);
    }

    @Override
    public boolean advanceVersion(Long accountId, long expectedVersion) {
        return mapper.advanceVersion(accountId, expectedVersion) == 1;
    }

    private Account toDomain(AccountPO po) {
        return Account.restore(po.getId(), po.getAccountName(), po.getPasswordHash(),
            po.getEmployeeId(), po.getOwnerId(),
            RecordStatus.valueOf(po.getStatus()),
            po.getVersion() == null ? 0 : po.getVersion());
    }

    private AccountPO toPO(Account account) {
        AccountPO po = new AccountPO();
        po.setId(account.id());
        po.setAccountName(account.accountName());
        po.setPasswordHash(account.passwordHash());
        po.setEmployeeId(account.employeeId());
        po.setOwnerId(account.ownerId());
        po.setStatus(account.status().name());
        po.setVersion(account.version());
        return po;
    }
}
