package com.huoling.wms.masterdata.identity.domain;

import java.util.List;
import java.util.Optional;

/**
 * 账号仓储接口。实现在 infrastructure，只能被本模块 application 注入（03）。
 */
public interface AccountRepository {

    Optional<Account> findById(Long id);

    Optional<Account> findByAccountName(String accountName);

    boolean existsByEmployeeId(Long employeeId);

    void insert(Account account);

    /**
     * 只写可变字段（状态、密码散列），不动 {@code version}：调用方必须先
     * {@link #advanceVersion(Long, long)} 成功抢到本次修改，版本推进由那一步负责（契约 API-008）。
     */
    void updateProfile(Long accountId, RecordStatus status, String passwordHash);

    List<Account> page(String keyword, RecordStatus status, int page, int size);

    long count(String keyword, RecordStatus status);

    /** 统计持有指定角色编码且状态为启用的账号数量，用于「至少保留一个权限管理员」。 */
    long countEnabledByRoleCode(String roleCode);

    /**
     * 按提交版本推进账号版本号：{@code update ... where id = ? and version = ?}。
     *
     * @return 受影响行数为 1 表示抢到这次修改；0 表示版本已被他人推进（COMMON_9004）
     */
    boolean advanceVersion(Long accountId, long expectedVersion);
}
