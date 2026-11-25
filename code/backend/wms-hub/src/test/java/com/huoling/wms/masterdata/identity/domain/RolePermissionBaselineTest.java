package com.huoling.wms.masterdata.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05「角色与模块的读写矩阵」第 201 行「账号、角色、权限」→ 初始权限点的机械推导结果。
 */
class RolePermissionBaselineTest {

    @Test
    @DisplayName("矩阵该行共十个权限点：账号四个、角色五个、审计一个")
    void 模块权限点全集() {
        assertThat(RolePermissionBaseline.accountRolePermissionModuleCodes())
            .containsExactlyInAnyOrder(
                "hub.account.read", "hub.account.create", "hub.account.update", "hub.account.role",
                "hub.role.read", "hub.role.create", "hub.role.update", "hub.role.assign",
                "hub.role.scope", "hub.audit.read")
            .hasSize(10);
    }

    @Test
    @DisplayName("权限管理员标「写」，绑定该行全部十个权限点（含审计只读）")
    void 权限管理员取全集() {
        assertThat(RolePermissionBaseline.defaultCodesFor("PERMISSION_ADMIN"))
            .isEqualTo(RolePermissionBaseline.accountRolePermissionModuleCodes())
            .contains("hub.audit.read");
    }

    @Test
    @DisplayName("主数据管理员标「读」，只绑只读动作")
    void 主数据管理员只读() {
        assertThat(RolePermissionBaseline.defaultCodesFor("MASTER_DATA_ADMIN"))
            .containsExactly("hub.account.read");
    }

    @Test
    @DisplayName("矩阵标「—」的四个角色无初始绑定")
    void 其余角色无绑定() {
        assertThat(RolePermissionBaseline.defaultCodesFor("WAREHOUSE_NETWORK_ADMIN")).isEmpty();
        assertThat(RolePermissionBaseline.defaultCodesFor("BILLING_SPECIALIST")).isEmpty();
        assertThat(RolePermissionBaseline.defaultCodesFor("RECONCILIATION_CLERK")).isEmpty();
        assertThat(RolePermissionBaseline.defaultCodesFor("CROSS_WAREHOUSE_DISPATCHER")).isEmpty();
    }

    @Test
    @DisplayName("基线覆盖六个角色，未知角色不猜绑定")
    void 基线范围() {
        assertThat(RolePermissionBaseline.roleCodes()).containsExactly(
            "MASTER_DATA_ADMIN", "WAREHOUSE_NETWORK_ADMIN", "PERMISSION_ADMIN",
            "BILLING_SPECIALIST", "RECONCILIATION_CLERK", "CROSS_WAREHOUSE_DISPATCHER");
        assertThat(RolePermissionBaseline.defaultCodesFor("NO_SUCH_ROLE")).isEqualTo(Set.of());
    }

    @Test
    @DisplayName("绑定的编码都符合权限点编码规则")
    void 编码格式合法() {
        for (String roleCode : RolePermissionBaseline.roleCodes()) {
            for (String code : RolePermissionBaseline.defaultCodesFor(roleCode)) {
                assertThat(Permission.declare(1L, code, "ACCOUNT").permCode()).isEqualTo(code);
            }
        }
    }
}
