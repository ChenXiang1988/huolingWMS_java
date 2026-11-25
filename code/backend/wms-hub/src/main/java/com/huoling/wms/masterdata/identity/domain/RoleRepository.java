package com.huoling.wms.masterdata.identity.domain;

import java.util.List;
import java.util.Optional;

/**
 * 角色仓储接口。
 */
public interface RoleRepository {

    Optional<Role> findById(Long id);

    Optional<Role> findByCode(String roleCode);

    /** 全部角色，按主键升序；契约 API-010 的 `client` 缺省时不过滤。 */
    List<Role> findAll();

    List<Role> findByClient(ClientType client);

    List<Role> findByIds(List<Long> ids);

    void insert(Role role);

    /**
     * 只写可变字段（名称、状态），不动 {@code version}：调用方必须先
     * {@link #advanceVersion(Long, long)} 成功抢到本次修改，版本推进由那一步负责（契约 API-012）。
     */
    void updateProfile(Long roleId, String roleName, RecordStatus status);

    List<Long> findRoleIdsByAccount(Long accountId);

    void replaceAccountRoles(Long accountId, List<Long> roleIds);

    List<String> findPermissionCodesByRole(Long roleId);

    void replaceRolePermissions(Long roleId, List<String> permissionCodes);

    /**
     * 该角色是否已有任何权限点绑定（含已停用权限点的绑定）。
     * 启动初始化据此判定「只在绑定为空时补基线」，不覆盖手工调整（05、19）。
     */
    boolean hasAnyPermissions(Long roleId);

    /**
     * 按权限点编码补绑定，已存在的不覆盖、不存在或已停用的权限点跳过。
     *
     * @return 实际新增的绑定条数；重复执行返回 0
     */
    int addPermissionsIfAbsent(Long roleId, List<String> permissionCodes);

    /**
     * 按提交版本推进角色版本号：{@code update ... where id = ? and version = ?}。
     *
     * @return 受影响行数为 1 表示抢到这次修改；0 表示版本已被他人推进（COMMON_9004）
     */
    boolean advanceVersion(Long roleId, long expectedVersion);
}
