package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RolePermissionBaseline;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 角色与权限点的初始绑定，紧随权限点同步之后执行（05「角色与模块的读写矩阵」、19「授权与初始化」）。
 *
 * <p>为什么不放 Flyway：权限点由 {@link PermissionStartupSync} 在应用启动时按代码声明写入，
 * Flyway 在 Spring 上下文初始化阶段就已跑完，那时 {@code wms_permission} 还是空表，
 * 用 SQL 绑定只能绑定到零行；而可重复执行脚本只在校验和变化时重跑，之后也不会补上。
 * 因此绑定必须排在权限点同步之后，作为应用启动链的一环：{@code @Order(30)} 在
 * {@code PermissionStartupSync}（{@code @Order(20)}）之后、权限对外提供之前完成。
 *
 * <p>幂等与不覆盖手工调整的策略：<b>只在某角色尚无任何绑定时按基线初始化一次</b>。
 * 理由有三：
 * <ol>
 *   <li>{@code 05} 的矩阵只到模块级，按钮级编码仍在并入矩阵（本变更设计章「权限点编码」）；
 *       这份映射是初始化基线，不是运行时权威，每次启动都按它重置会把权限管理员经
 *       API-013 做的调整（例如给对账员加 {@code hub.audit.read}）悄悄冲掉；</li>
 *   <li>授权动作的权威路径是 API-013 整体替换，且每次变更写审计（19「授权动作不能自授，也必须有痕」）；
 *       启动流程重写绑定会绕开审计，产生「没人操作但权限变了」的账实不符；</li>
 *   <li>「绑定为空即补」的代价是：若有人把某角色的绑定清空重启，基线会回填一次。
 *       清空后回填的是文档化的默认值，可解释，且比「静默覆盖」可接受。</li>
 * </ol>
 */
@Component
@Order(30)
public class RolePermissionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionInitializer.class);

    private final RoleRepository roleRepository;
    private final PermissionCache permissionCache;

    public RolePermissionInitializer(RoleRepository roleRepository, PermissionCache permissionCache) {
        this.roleRepository = roleRepository;
        this.permissionCache = permissionCache;
    }

    @Override
    public void run(ApplicationArguments args) {
        int insertedTotal = 0;
        for (String roleCode : RolePermissionBaseline.roleCodes()) {
            Set<String> codes = RolePermissionBaseline.defaultCodesFor(roleCode);
            if (codes.isEmpty()) {
                // 矩阵该行是「—」，无需绑定；若有人手工授予过，也不在这里回收。
                continue;
            }
            Optional<Role> role = roleRepository.findByCode(roleCode);
            if (role.isEmpty()) {
                log.warn("角色 {} 不存在，跳过权限点初始化（应由 R__hub_roles.sql 建出）", roleCode);
                continue;
            }
            Long roleId = role.get().id();
            if (roleRepository.hasAnyPermissions(roleId)) {
                log.info("角色 {} 已有权限点绑定，跳过初始化，保留现有配置", roleCode);
                continue;
            }
            int inserted = roleRepository.addPermissionsIfAbsent(roleId, List.copyOf(codes));
            insertedTotal += inserted;
            log.info("角色 {} 已按 05 矩阵初始化权限点 {} 个", roleCode, inserted);
        }
        if (insertedTotal > 0) {
            // 绑定变了，旧键自然不再命中（决三：全局版本号失效）。
            permissionCache.bumpVersion();
            log.info("角色初始权限点补录完成，共 {} 条绑定", insertedTotal);
        } else {
            log.info("角色初始权限点无需补录");
        }
    }
}
