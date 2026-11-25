package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 初始管理员引导：账号表为空且配置了引导参数时创建权限管理员，只在冷启动生效。
 * 密码从配置注入，仓库不存明文。
 */
@Component
@Order(10)
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    private static final String ADMIN_ROLE_CODE = "PERMISSION_ADMIN";

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminName;
    private final String adminPassword;

    public BootstrapAdmin(AccountRepository accountRepository,
                          RoleRepository roleRepository,
                          PasswordEncoder passwordEncoder,
                          @Value("${wms.bootstrap.admin-name:}") String adminName,
                          @Value("${wms.bootstrap.admin-password:}") String adminPassword) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminName = adminName;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminName == null || adminName.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.info("未配置引导管理员，跳过");
            return;
        }
        if (accountRepository.findByAccountName(adminName).isPresent()) {
            log.info("引导管理员已存在，跳过");
            return;
        }
        Account account = Account.create(null, adminName,
            passwordEncoder.encode(adminPassword), 1L, null);
        accountRepository.insert(account);
        Account saved = accountRepository.findByAccountName(adminName).orElseThrow();
        Optional<Role> role = roleRepository.findByCode(ADMIN_ROLE_CODE);
        if (role.isPresent()) {
            roleRepository.replaceAccountRoles(saved.id(), List.of(role.get().id()));
        }
        log.info("已创建引导管理员 {}", adminName);
    }
}
