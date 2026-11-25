package com.huoling.wms.masterdata.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataScopeMergerTest {

    @Test
    @DisplayName("同维度多条角色取并集")
    void 同维度并集() {
        RoleScope a = RoleScope.define(1L, 10L, ScopeDimension.WAREHOUSE, ScopeType.LIST, Set.of(1L));
        RoleScope b = RoleScope.define(2L, 11L, ScopeDimension.WAREHOUSE, ScopeType.LIST, Set.of(2L, 3L));
        DataScope scope = DataScopeMerger.merge(List.of(a, b));
        assertThat(scope.allWarehouses()).isFalse();
        assertThat(scope.warehouseIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("出现全部范围时按全部")
    void 全部覆盖列表() {
        RoleScope a = RoleScope.define(1L, 10L, ScopeDimension.OWNER, ScopeType.LIST, Set.of(9L));
        RoleScope b = RoleScope.define(2L, 11L, ScopeDimension.OWNER, ScopeType.ALL, Set.of());
        DataScope scope = DataScopeMerger.merge(List.of(a, b));
        assertThat(scope.allOwners()).isTrue();
        assertThat(scope.coversOwner(123L)).isTrue();
    }

    @Test
    @DisplayName("未配置的维度按全部，不因缺配置无法作业")
    void 未配置按全部() {
        DataScope scope = DataScopeMerger.merge(List.of());
        assertThat(scope.allOwners()).isTrue();
        assertThat(scope.allWarehouses()).isTrue();
        assertThat(scope.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("指定列表为空的域判为空，必须拒绝")
    void 空列表拒绝() {
        DataScope empty = new DataScope(false, Set.of(), true, Set.of());
        assertThat(empty.isEmpty()).isTrue();
        assertThatThrownBy(() -> {
            if (empty.isEmpty()) {
                throw new IdentityException(IdentityErrorCodes.AUTHZ_EMPTY_SCOPE, "数据域为空，拒绝访问");
            }
        }).isInstanceOf(IdentityException.class);
    }
}
