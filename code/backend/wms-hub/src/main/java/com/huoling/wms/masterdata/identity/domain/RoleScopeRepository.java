package com.huoling.wms.masterdata.identity.domain;

import java.util.List;

/**
 * 数据域仓储接口。
 */
public interface RoleScopeRepository {

    List<RoleScope> findByRole(Long roleId);

    void replaceAll(Long roleId, List<RoleScope> scopes);
}
