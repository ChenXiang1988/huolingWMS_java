package com.huoling.wms.masterdata.identity.domain;

import com.huoling.wms.common.domain.AggregateRoot;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 角色数据域聚合。只允许货主与仓两个维度；指定列表必须有具体标识，全部范围不得携带标识（19）。
 */
@AggregateRoot
public class RoleScope {

    private final Long id;
    private final Long roleId;
    private final ScopeDimension dimension;
    private ScopeType scopeType;
    private final Set<Long> targetIds;

    private RoleScope(Long id, Long roleId, ScopeDimension dimension,
                      ScopeType scopeType, Set<Long> targetIds) {
        this.id = id;
        this.roleId = roleId;
        this.dimension = dimension;
        this.scopeType = scopeType;
        this.targetIds = targetIds;
    }

    public static RoleScope define(Long id, Long roleId, ScopeDimension dimension,
                                   ScopeType scopeType, Set<Long> targetIds) {
        if (dimension == null) {
            throw new IdentityException(IdentityErrorCodes.SCOPE_DIMENSION_INVALID,
                "数据域维度只支持货主与仓");
        }
        if (scopeType == null) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, "范围类型不能为空");
        }
        Set<Long> targets = targetIds == null ? Set.of() : new LinkedHashSet<>(targetIds);
        validate(scopeType, targets);
        return new RoleScope(id, roleId, dimension, scopeType, targets);
    }

    public void replace(ScopeType newType, Set<Long> newTargetIds) {
        Set<Long> targets = newTargetIds == null ? Set.of() : new LinkedHashSet<>(newTargetIds);
        validate(newType, targets);
        this.scopeType = newType;
        this.targetIds.clear();
        this.targetIds.addAll(targets);
    }

    public boolean covers(Long targetId) {
        return scopeType == ScopeType.ALL || targetIds.contains(targetId);
    }

    public boolean isEmpty() {
        return scopeType == ScopeType.LIST && targetIds.isEmpty();
    }

    private static void validate(ScopeType type, Set<Long> targets) {
        if (type == ScopeType.LIST && targets.isEmpty()) {
            throw new IdentityException(IdentityErrorCodes.SCOPE_TARGET_INVALID,
                "指定列表必须给出至少一个标识");
        }
        if (type == ScopeType.ALL && !targets.isEmpty()) {
            throw new IdentityException(IdentityErrorCodes.SCOPE_TARGET_INVALID,
                "全部范围不得携带具体标识");
        }
    }

    public Long id() {
        return id;
    }

    public Long roleId() {
        return roleId;
    }

    public ScopeDimension dimension() {
        return dimension;
    }

    public ScopeType scopeType() {
        return scopeType;
    }

    public Set<Long> targetIds() {
        return Collections.unmodifiableSet(targetIds);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoleScope other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
