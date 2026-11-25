package com.huoling.wms.masterdata.identity.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多角色数据域合并：同维度取并集，出现全部则按全部，未配置维度按全部；任一维度为空的指定列表直接拒绝。
 * 跨维度不在这里算交集——交集在查询条件里以 AND 表达（19）。
 */
public final class DataScopeMerger {

    private DataScopeMerger() {
    }

    public static DataScope merge(List<RoleScope> scopes) {
        DimensionResult owners = mergeDimension(scopes, ScopeDimension.OWNER);
        DimensionResult warehouses = mergeDimension(scopes, ScopeDimension.WAREHOUSE);
        return new DataScope(owners.all, owners.ids, warehouses.all, warehouses.ids);
    }

    private static DimensionResult mergeDimension(List<RoleScope> scopes, ScopeDimension dimension) {
        List<RoleScope> matched = scopes == null ? List.of()
            : scopes.stream().filter(scope -> scope.dimension() == dimension).toList();
        if (matched.isEmpty()) {
            return new DimensionResult(true, Set.of());
        }
        if (matched.stream().anyMatch(scope -> scope.scopeType() == ScopeType.ALL)) {
            return new DimensionResult(true, Set.of());
        }
        Set<Long> ids = new LinkedHashSet<>();
        matched.forEach(scope -> ids.addAll(scope.targetIds()));
        if (ids.isEmpty()) {
            throw new IdentityException(IdentityErrorCodes.AUTHZ_EMPTY_SCOPE,
                "数据域为空，拒绝访问");
        }
        return new DimensionResult(false, ids);
    }

    private record DimensionResult(boolean all, Set<Long> ids) {
    }
}
