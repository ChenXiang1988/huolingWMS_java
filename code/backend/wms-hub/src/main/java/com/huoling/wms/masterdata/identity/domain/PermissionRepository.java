package com.huoling.wms.masterdata.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 权限点仓储接口。权限点只由启动同步写入，界面只能读（19）。
 */
public interface PermissionRepository {

    List<Permission> findAll();

    List<Permission> findAllEnabled();

    Optional<Permission> findByCode(String permCode);

    /**
     * 按端解析账号的功能权限点：只取 {@code applies_to_client} 等于该端的角色（05：不跨端合并）。
     *
     * <p>端标识必须由调用方从令牌给出：同一账号可能同时挂多端角色，
     * 固定按某一端解析会让另一端的接口恒无权限。
     */
    Set<String> findCodesByAccount(Long accountId, ClientType client);

    void insert(Permission permission);

    void updateStatus(Long id, RecordStatus status);

    void updateModule(Long id, String module);
}
