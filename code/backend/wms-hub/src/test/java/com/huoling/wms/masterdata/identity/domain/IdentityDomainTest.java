package com.huoling.wms.masterdata.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityDomainTest {

    @Test
    @DisplayName("操作者与目标账号相同，拒绝授权")
    void 不能自授() {
        assertThatThrownBy(() -> AuthorizationRules.assertNotSelf(100L, 100L))
            .isInstanceOf(IdentityException.class)
            .extracting(e -> ((IdentityException) e).code())
            .isEqualTo(IdentityErrorCodes.AUTHZ_SELF_OPERATION);
    }

    @Test
    @DisplayName("剩余权限管理员为零，拒绝变更")
    void 至少保留一个权限管理员() {
        assertThatThrownBy(() -> AuthorizationRules.assertPermissionAdminRemains(0))
            .isInstanceOf(IdentityException.class)
            .extracting(e -> ((IdentityException) e).code())
            .isEqualTo(IdentityErrorCodes.AUTHZ_LAST_ADMIN);

        AuthorizationRules.assertPermissionAdminRemains(1);
    }

    @Test
    @DisplayName("角色端与账号端不一致，拒绝授予")
    void 角色不跨端授予() {
        assertThatThrownBy(() ->
            AuthorizationRules.assertClientMatches(ClientType.SITE, ClientType.HUB))
            .isInstanceOf(IdentityException.class)
            .extracting(e -> ((IdentityException) e).code())
            .isEqualTo(IdentityErrorCodes.ROLE_CLIENT_MISMATCH);
    }

    @Test
    @DisplayName("账号不能同时绑定员工与货主")
    void 账号绑定唯一主体() {
        assertThatThrownBy(() -> Account.create(1L, "hub.admin", "hash", 10L, 20L))
            .isInstanceOf(IdentityException.class);
    }

    @Test
    @DisplayName("账号名格式非法时拒绝创建")
    void 账号名格式校验() {
        assertThatThrownBy(() -> Account.create(1L, "带中文", "hash", 10L, null))
            .isInstanceOf(IdentityException.class);
    }

    @Test
    @DisplayName("停用账号后状态变为停用")
    void 停用账号() {
        Account account = Account.create(1L, "hub.admin", "hash", 10L, null);
        assertThat(account.enabled()).isTrue();
        account.disable();
        assertThat(account.enabled()).isFalse();
    }

    @Test
    @DisplayName("指定列表为空时拒绝配置数据域")
    void 指定列表必须有标识() {
        assertThatThrownBy(() ->
            RoleScope.define(1L, 2L, ScopeDimension.WAREHOUSE, ScopeType.LIST, Set.of()))
            .isInstanceOf(IdentityException.class)
            .extracting(e -> ((IdentityException) e).code())
            .isEqualTo(IdentityErrorCodes.SCOPE_TARGET_INVALID);
    }

    @Test
    @DisplayName("全部范围不得携带具体标识")
    void 全部范围不带标识() {
        assertThatThrownBy(() ->
            RoleScope.define(1L, 2L, ScopeDimension.OWNER, ScopeType.ALL, Set.of(9L)))
            .isInstanceOf(IdentityException.class);
    }

    @Test
    @DisplayName("权限点编码必须形如 hub.account.read")
    void 权限点编码格式() {
        assertThatThrownBy(() -> Permission.declare(1L, "account-read", "M01"))
            .isInstanceOf(IdentityException.class);
        Permission ok = Permission.declare(2L, "hub.account.read", "M01");
        assertThat(ok.permCode()).isEqualTo("hub.account.read");
    }
}
