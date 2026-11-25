package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.api.RequiresPermission;
import com.huoling.wms.masterdata.identity.domain.Permission;
import com.huoling.wms.masterdata.identity.domain.PermissionRepository;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 启动时对账权限点：代码有而表无则插入，表有而代码无则置停用，白名单外缺注解则启动失败（19）。
 *
 * <p>顺序固定：本类 {@code @Order(20)} 先写完全局权限点，{@link RolePermissionInitializer}
 * {@code @Order(30)} 再按角色补初始绑定；权限点表为空时做绑定只会绑到零行，顺序不能颠倒。
 * 引导管理员 {@code @Order(10)} 更早，只建账号与角色，不依赖权限点。
 */
@Component
@Order(20)
public class PermissionStartupSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionStartupSync.class);

    private static final Set<String> WHITELIST = Set.of(
        "/hub/api/auth/login", "/hub/api/auth/refresh",
        "/hub/api/auth/logout", "/hub/api/auth/me",
        // 单仓端会话入口（API-018、API-019）：靠站点凭证与刷新凭证自证，没有权限点注解。
        "/api/hub/auth/site-login", "/api/hub/auth/refresh",
        "/actuator/health", "/actuator/info", "/v3/api-docs", "/error");

    private final RequestMappingHandlerMapping handlerMapping;
    private final PermissionRepository permissionRepository;
    private final PermissionCache permissionCache;

    public PermissionStartupSync(@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
                                 RequestMappingHandlerMapping handlerMapping,
                                 PermissionRepository permissionRepository,
                                 PermissionCache permissionCache) {
        this.handlerMapping = handlerMapping;
        this.permissionRepository = permissionRepository;
        this.permissionCache = permissionCache;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> declared = new HashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            String path = firstPath(info);
            RequiresPermission annotation = method.getMethodAnnotation(RequiresPermission.class);
            if (annotation == null) {
                if (!allowedWithoutPermission(path)) {
                    throw new IllegalStateException(
                        "接口 " + method.getMethod().getDeclaringClass().getSimpleName()
                            + "#" + method.getMethod().getName()
                            + "（" + path + "）未声明权限点，应用拒绝启动");
                }
                return;
            }
            declared.put(annotation.value(), moduleOf(annotation.value()));
        });

        Set<String> existing = new HashSet<>();
        for (Permission permission : permissionRepository.findAll()) {
            existing.add(permission.permCode());
            if (!declared.containsKey(permission.permCode())
                && permission.status() == RecordStatus.ENABLED) {
                permissionRepository.updateStatus(permission.id(), RecordStatus.DISABLED);
                log.info("权限点 {} 已下线，置为停用", permission.permCode());
            }
        }
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                permissionRepository.insert(Permission.declare(null, entry.getKey(), entry.getValue()));
                log.info("权限点 {} 已登记", entry.getKey());
            }
        }
        permissionCache.bumpVersion();
        log.info("权限点同步完成，共 {} 条声明", declared.size());
    }

    private String firstPath(RequestMappingInfo info) {
        return info.getPatternValues().stream().findFirst().orElse("");
    }

    /**
     * 无需权限点的路径：认证入口、健康与文档、以及受理结果查询（只需令牌与本人校验）。
     */
    private boolean allowedWithoutPermission(String path) {
        return WHITELIST.contains(path)
            || path.startsWith("/hub/api/acceptances/")
            || path.startsWith("/v3/api-docs")
            || path.startsWith("/swagger-ui")
            || path.startsWith("/actuator");
    }

    private String moduleOf(String permCode) {
        String[] parts = permCode.split("\\.");
        return parts.length >= 2 ? parts[1].toUpperCase() : "COMMON";
    }
}
