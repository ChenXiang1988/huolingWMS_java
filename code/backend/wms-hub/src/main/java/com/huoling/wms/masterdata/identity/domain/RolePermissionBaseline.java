package com.huoling.wms.masterdata.identity.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营端六个角色的初始权限点，由 05「角色与模块的读写矩阵」机械推导（19「授权与初始化」）。
 *
 * <p>推导口径：本变更声明的十个权限点全部落在矩阵的「账号、角色、权限」这一行
 * （{@code 05} 第 201 行），因此只取该行的读写标记：
 * <ul>
 *   <li>权限管理员标「写」→ 该行全部十个权限点；</li>
 *   <li>主数据管理员标「读」→ 只读动作 {@code hub.account.read}；</li>
 *   <li>其余四个角色标「—」→ 无绑定。</li>
 * </ul>
 * 审计日志 {@code hub.audit.read} 归权限管理员，依据契约 API-017「权限管理员，审计只读岗位复用该角色」。
 *
 * <p>这份映射只是**初始化基线**，不是运行时权威：{@code 05} 明确「按钮级由权限点清单决定」，
 * 按钮级编码仍在并入矩阵的过程中。应用启动只在某角色尚无任何绑定时按本表补一次，
 * 已有绑定（含权限管理员后续经 API-013 手工调整过的）一律不动，运行时仍以
 * {@code wms_role_permission} 为准。
 */
public final class RolePermissionBaseline {

    /** 矩阵「账号、角色、权限」行的写集合：十个权限点全集。 */
    private static final Set<String> ACCOUNT_ROLE_PERMISSION_MODULE = ordered(
        "hub.account.read",
        "hub.account.create",
        "hub.account.update",
        "hub.account.role",
        "hub.role.read",
        "hub.role.create",
        "hub.role.update",
        "hub.role.assign",
        "hub.role.scope",
        "hub.audit.read");

    /** 角色编码 → 初始权限点；顺序与 {@code R__hub_roles.sql} 一致，便于日志对照。 */
    private static final Map<String, Set<String>> BASELINE = baseline();

    private RolePermissionBaseline() {
    }

    /**
     * 某角色的初始权限点集合。矩阵标「—」的角色返回空集，未知角色同样返回空集
     * （未知角色不猜绑定，由权限管理员经 API-013 显式授予）。
     */
    public static Set<String> defaultCodesFor(String roleCode) {
        return BASELINE.getOrDefault(roleCode, Set.of());
    }

    /** 纳入基线的全部角色编码，顺序稳定。 */
    public static Set<String> roleCodes() {
        return BASELINE.keySet();
    }

    /** 矩阵「账号、角色、权限」行的写集合，供启动日志与对账使用。 */
    public static Set<String> accountRolePermissionModuleCodes() {
        return ACCOUNT_ROLE_PERMISSION_MODULE;
    }

    private static Map<String, Set<String>> baseline() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put("MASTER_DATA_ADMIN", ordered("hub.account.read"));
        map.put("WAREHOUSE_NETWORK_ADMIN", Set.of());
        map.put("PERMISSION_ADMIN", ACCOUNT_ROLE_PERMISSION_MODULE);
        map.put("BILLING_SPECIALIST", Set.of());
        map.put("RECONCILIATION_CLERK", Set.of());
        map.put("CROSS_WAREHOUSE_DISPATCHER", Set.of());
        return Collections.unmodifiableMap(map);
    }

    private static Set<String> ordered(String... codes) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(codes)));
    }
}
