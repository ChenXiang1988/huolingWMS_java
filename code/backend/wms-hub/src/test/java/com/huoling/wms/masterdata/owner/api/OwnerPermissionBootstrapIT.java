package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限点初始化脚本的效果验收（tasks 1.16、契约 E-20、`05` 的主数据列）。
 *
 * <p>脚本是 repeatable 的初始绑定，运行期权威路径是权限管理员的整体替换；本类只验证
 * 「发布态生效的那一份」：两项权限点的模块名、主数据管理员的读写、其余五个运营角色的只读，
 * 以及为持 {@code archive.hub.create} 的角色补授 {@code owner.hub.read} 的兜底。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerPermissionBootstrapIT extends OwnerApiTestSupport {

    @Test
    @DisplayName("两枚权限点登记为 OWNER 模块，与 24 的 M01 货主对齐")
    void 权限点登记() {
        List<String> modules = jdbc.queryForList(
            "select module from wms_permission where perm_code in ('owner.hub.read','owner.hub.write') "
                + "order by perm_code", String.class);

        assertThat(modules).containsExactly("OWNER", "OWNER");
    }

    @Test
    @DisplayName("主数据管理员可读可写，其余五个运营角色只读")
    void 角色绑定() {
        assertThat(rolesHolding("owner.hub.write")).containsExactly("MASTER_DATA_ADMIN");
        assertThat(rolesHolding("owner.hub.read")).containsExactlyInAnyOrder(
            "MASTER_DATA_ADMIN", "WAREHOUSE_NETWORK_ADMIN", "PERMISSION_ADMIN",
            "BILLING_SPECIALIST", "RECONCILIATION_CLERK", "CROSS_WAREHOUSE_DISPATCHER");
    }

    @Test
    @DisplayName("持 archive.hub.create 的角色都补授了 owner.hub.read（API-020 换点不落 403）")
    void 代建点持有者补授读权限() {
        List<String> missing = jdbc.queryForList("""
            select distinct r.role_code
              from wms_role_permission rp
              join wms_role r on r.id = rp.role_id
              join wms_permission p on p.id = rp.permission_id
             where p.perm_code = 'archive.hub.create'
               and not exists (
                 select 1
                   from wms_role_permission rp2
                   join wms_permission p2 on p2.id = rp2.permission_id
                  where rp2.role_id = rp.role_id and p2.perm_code = 'owner.hub.read')
            """, String.class);

        assertThat(missing).as("凡持代建点的角色必须持 owner.hub.read").isEmpty();
    }

    private List<String> rolesHolding(String permCode) {
        return jdbc.queryForList("""
            select r.role_code
              from wms_role_permission rp
              join wms_role r on r.id = rp.role_id
              join wms_permission p on p.id = rp.permission_id
             where p.perm_code = ?
             order by r.role_code
            """, String.class, permCode);
    }
}
