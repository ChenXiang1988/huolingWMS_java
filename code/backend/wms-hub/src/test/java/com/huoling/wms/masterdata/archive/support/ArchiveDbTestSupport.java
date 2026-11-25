package com.huoling.wms.masterdata.archive.support;

import com.huoling.wms.masterdata.archive.application.ArchiveAcceptanceService;
import com.huoling.wms.masterdata.archive.application.ArchiveAccessGuard;
import com.huoling.wms.masterdata.archive.application.ArchiveIdempotencyService;
import com.huoling.wms.masterdata.archive.application.ChangeRequestService;
import com.huoling.wms.masterdata.archive.application.SkuArchiveCommandService;
import com.huoling.wms.masterdata.archive.application.SkuArchiveQueryService;
import com.huoling.wms.masterdata.archive.application.SkuReviewService;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.domain.SkuArchiveFields;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连真 PostgreSQL 的集成测试支撑：造数自造自清，用例之间不依赖执行顺序。
 *
 * <p>只用真实数据库：部分唯一索引、CHECK、触发器与事务语义换库即失真（硬约束 1）。
 * 不预置人工数据，也不依赖种子业务数据：货主标识、账号、角色、档案编号全部由本类生成，
 * 每次用例结束按登记表删除，跑完库里不留痕（硬约束 6）。
 *
 * <p>标识区间取 {@code 7_100_000_000_000} 起：与雪花主键和本地手工数据都不重叠，
 * 即使同一库上还有别的进程写入，也不会被本类的清理误删。
 */
public abstract class ArchiveDbTestSupport {

    /**
     * 造数标识起点：每次 JVM 启动取一个不同的基址。
     *
     * <p>只用进程内自增会让两次运行的标识完全相同，上一次运行留下的遗漏行就会和这一次撞上；
     * 基址随运行变化后，每次运行的造数互不相干。
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(
        7_100_000_000_000L + ProcessHandle.current().pid() * 1_000_000L
            + (System.nanoTime() & 0xFFFFFL));

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SkuArchiveCommandService commandService;

    @Autowired
    protected SkuArchiveQueryService queryService;

    @Autowired
    protected SkuReviewService reviewService;

    @Autowired
    protected ChangeRequestService changeRequestService;

    @Autowired
    protected ArchiveAcceptanceService acceptanceService;

    @Autowired
    protected ArchiveIdempotencyService idempotencyService;

    @Autowired
    protected SkuArchiveRepository archiveRepository;

    @Autowired
    protected ArchiveAccessGuard accessGuard;

    private final Set<Long> owners = new LinkedHashSet<>();
    private final Set<Long> accounts = new LinkedHashSet<>();
    private final Set<Long> roles = new LinkedHashSet<>();
    private final Set<String> acceptNos = new LinkedHashSet<>();
    private final Set<String> idempotencyKeys = new LinkedHashSet<>();

    @AfterEach
    void cleanUpCreatedRows() {
        // 依赖方向由弱到强删：留痕与消息表先删，主表最后删（INV-18 只约束业务数据，测试自清是运维动作）。
        // 幂等记录按「键」删而不是按作用域删：受理作用域由用例与控制器各自决定，按键才覆盖得全。
        for (String key : idempotencyKeys) {
            jdbc.update("delete from hub_idempotency where idempotency_key = ?", key);
            jdbc.update("delete from hub_archive_acceptance where idempotency_key = ?", key);
        }
        for (String acceptNo : acceptNos) {
            jdbc.update("delete from hub_archive_acceptance where accept_no = ?", acceptNo);
        }
        for (Long owner : owners) {
            jdbc.update("delete from hub_sku_review where owner_id = ?", owner);
            jdbc.update("delete from hub_outbox where owner_id = ?", owner);
            jdbc.update("delete from hub_product_change_request where owner_id = ?", owner);
            jdbc.update("delete from hub_sku_archive where owner_id = ?", owner);
        }
        for (Long role : roles) {
            jdbc.update("delete from wms_role_scope_item where role_scope_id in "
                + "(select id from wms_role_scope where role_id = ?)", role);
            jdbc.update("delete from wms_role_scope where role_id = ?", role);
            jdbc.update("delete from wms_account_role where role_id = ?", role);
            jdbc.update("delete from wms_role_permission where role_id = ?", role);
            jdbc.update("delete from wms_role where id = ?", role);
        }
        for (Long account : accounts) {
            jdbc.update("delete from wms_account_role where account_id = ?", account);
            jdbc.update("delete from wms_account where id = ?", account);
        }
        owners.clear();
        accounts.clear();
        roles.clear();
        acceptNos.clear();
        idempotencyKeys.clear();
    }

    /** 新标识：所有造数都用它，避免与库中已有数据相撞。 */
    protected static long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    /** 登记一个归属货主，用例结束整片清理。 */
    protected long registerOwner() {
        long ownerId = nextId();
        owners.add(ownerId);
        return ownerId;
    }

    /** 登记一个受理号，用例结束删除其受理记录。 */
    protected void registerAcceptNo(String acceptNo) {
        if (acceptNo != null) {
            acceptNos.add(acceptNo);
        }
    }

    /** 登记一条幂等记录对应的键，用例结束按它删除受理记录与幂等记录。 */
    protected void registerIdempotencyKey(String key) {
        if (key != null) {
            idempotencyKeys.add(key);
        }
    }

    /**
     * 造一个货主端账号：绑定归属货主，并挂上种子角色 {@code OWNER_USER}。
     *
     * <p>权限绑定复用 {@code R__hub_archive_permissions.sql} 的初始绑定，不另造权限点，
     * 这样「功能权限校验」测的是发布态的真实授权，不是测试自造的影子数据。
     */
    protected long createOwnerAccount(long ownerId) {
        long accountId = insertAccount("t-owner-" + nextId(), ownerId);
        bindSeedRole(accountId, "OWNER_USER");
        return accountId;
    }

    /** 造一个运营端账号，挂种子角色 {@code MASTER_DATA_ADMIN}（含档案读、代建与审核权限）。 */
    protected long createHubAdminAccount() {
        long accountId = insertAccount("t-hub-" + nextId(), null);
        bindSeedRole(accountId, "MASTER_DATA_ADMIN");
        return accountId;
    }

    /** 造一个没有货主绑定的账号（货主端越权的反例）。 */
    protected long createAccountWithoutOwner() {
        return insertAccount("t-noscope-" + nextId(), null);
    }

    /**
     * 造一个单仓端账号：角色端标识为 SITE，仓数据域按给定仓标识建指定列表。
     *
     * @param warehouseIds 仓数据域；空集合表示「未配置仓维度」，按全部仓处理
     */
    protected long createSiteAccount(List<Long> warehouseIds) {
        long accountId = insertAccount("t-site-" + nextId(), null);
        long roleId = insertRole("T_SITE_" + nextId(), "SITE");
        bindAccountRole(accountId, roleId);
        if (!warehouseIds.isEmpty()) {
            long scopeId = insertRoleScope(roleId, "WAREHOUSE", "LIST");
            for (Long warehouseId : warehouseIds) {
                jdbc.update("insert into wms_role_scope_item (id, role_scope_id, target_id) "
                    + "values (?, ?, ?)", nextId(), scopeId, warehouseId);
            }
        }
        return accountId;
    }

    /**
     * 造一个单仓端账号，并额外把货主数据域限定为指定列表。
     *
     * <p>单仓只能对本仓可见档案发起变更申请，货主维度被限定后，别人的档案即越权（INV-19）。
     */
    protected long createSiteAccountScopedToOwners(List<Long> warehouseIds, List<Long> ownerIds) {
        long accountId = createSiteAccount(warehouseIds);
        Long roleId = jdbc.queryForObject(
            "select role_id from wms_account_role where account_id = ?", Long.class, accountId);
        long scopeId = insertRoleScope(roleId, "OWNER", "LIST");
        for (Long ownerId : ownerIds) {
            jdbc.update("insert into wms_role_scope_item (id, role_scope_id, target_id) "
                + "values (?, ?, ?)", nextId(), scopeId, ownerId);
        }
        return accountId;
    }

    /** 档案必填五项齐备的字段集，附属字段留空。 */
    protected static SkuArchiveFields fields(String ownerCode, String barcode, String name) {
        return new SkuArchiveFields(name, ownerCode, barcode, "瓶", "常温", "饮料", "550ml",
            null, null, null, null, new BigDecimal("0.55"), new BigDecimal("0.60"),
            new BigDecimal("0.001"), new BigDecimal("6"), new BigDecimal("6"),
            new BigDecimal("20"), false, false, false, false, false, 365, null, null);
    }

    private long insertAccount(String accountName, Long ownerId) {
        long accountId = nextId();
        accounts.add(accountId);
        // JDBC 驱动不能直接推断 java.time.Instant，造数统一用带偏移的时间
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into wms_account (id, account_name, password_hash, employee_id, owner_id, status,
                version, created_at, created_by, updated_at, updated_by)
            values (?, ?, ?, null, ?, 'ENABLED', 0, ?, 0, ?, 0)
            """, accountId, accountName, "$2a$10$testonlytestonlytestonlytestonlytestonlytestonly",
            ownerId, now, now);
        return accountId;
    }

    private void bindSeedRole(long accountId, String roleCode) {
        List<Long> roleIds = jdbc.queryForList(
            "select id from wms_role where role_code = ?", Long.class, roleCode);
        if (roleIds.isEmpty()) {
            // 种子角色缺失说明迁移没跑到：直接失败，不静默跳过权限断言。
            throw new IllegalStateException("种子角色不存在：" + roleCode + "，请确认 Flyway 迁移已执行");
        }
        jdbc.update("insert into wms_account_role (account_id, role_id) values (?, ?)",
            accountId, roleIds.get(0));
    }

    private long insertRole(String roleCode, String client) {
        long roleId = nextId();
        roles.add(roleId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into wms_role (id, role_code, role_name, applies_to_client, status, version,
                created_at, created_by, updated_at, updated_by)
            values (?, ?, ?, ?, 'ENABLED', 0, ?, 0, ?, 0)
            """, roleId, roleCode, roleCode, client, now, now);
        return roleId;
    }

    private void bindAccountRole(long accountId, long roleId) {
        jdbc.update("insert into wms_account_role (account_id, role_id) values (?, ?)",
            accountId, roleId);
    }

    private long insertRoleScope(long roleId, String dimension, String scopeType) {
        long scopeId = nextId();
        jdbc.update("insert into wms_role_scope (id, role_id, dimension, scope_type) "
            + "values (?, ?, ?, ?)", scopeId, roleId, dimension, scopeType);
        return scopeId;
    }
}
