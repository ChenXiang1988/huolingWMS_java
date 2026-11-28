package com.huoling.wms.masterdata.owner.infrastructure;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主档案的库层强制手段（design「强制手段分层」的「数据库约束」列，OI-01 至 OI-09、INV-18）。
 *
 * <p>逐条不变量都有一条「绕过应用直接写库」的用例：应用层拒绝是常态，库层兜底才是最后一道。
 * 只连真 PostgreSQL：{@code CHECK}、唯一索引、{@code BEFORE UPDATE} 触发器与列权限的语义在
 * 内存库里全不一样（部分条件下的触发器、{@code raise exception}、正则字符集），换库等于假绿。
 *
 * <p>用例自造，<b>不自清</b>：所有行由 {@code nextId()} 生成主键（每次运行换基址）与唯一信用代码，
 * 用完留在库里——{@code wms_owner} 的行级删除被 {@code trg_wms_owner_no_delete} 拒绝（INV-18），
 * 这是本类要证明的事实之一，不能为了清理把它绕开。
 * 断言一律打 {@code DataAccessException} 且校验报错里出现的那一列／那一条约束名，
 * 只断「抛了异常」会把「写错列」也当成通过。
 */
@SpringBootTest(classes = HubApplication.class)
class OwnerArchiveConstraintIT extends ArchiveDbTestSupport {

    /** 自动迁移目录在 classpath 上的位置，与 application.yml 的 flyway.locations 一致。 */
    private static final String MIGRATION_DIR = "db/migration/";

    /** 迁移脚本所在目录（相对模块目录 {@code code/backend/wms-hub}）。 */
    private static final Path MIGRATION_SOURCE_DIR =
        Path.of("../../db/migration").toAbsolutePath().normalize();

    /** 第二窗口运维脚本：补录落库与外键，刻意不在 Flyway 目录里（design「发布批次」）。 */
    private static final Path OPS_SCRIPT = Path.of(
        "../../db/ops/V20261138__hub_owner_import_and_foreign_keys.sql").toAbsolutePath().normalize();

    /** 本类直接插进 {@code wms_owner} 的行。 */
    private final Set<Long> rows = new LinkedHashSet<>();

    /** TRUNCATE 用例用它把语句包进事务：即使触发器意外失效，也不会真的清空表。 */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * 登记用，不做删除：{@code wms_owner} 上的 {@code trg_wms_owner_no_delete} 拒绝任何行级删除
     * （INV-18，属主也绕不过），清理不能靠删表——那会把「库层真的挡住了删除」这条事实抹掉。
     * 残留行按 {@code nextId()} 的每次运行不同基址与唯一信用代码区分，不影响本类断言。
     */
    @AfterEach
    void cleanRows() {
        rows.clear();
    }

    @ParameterizedTest(name = "{0} 为空即被库层拒绝")
    @ValueSource(strings = {"ownerName", "contactName", "contactPhone", "creditCode"})
    @DisplayName("四项必填：null 在库层不可写入（OI-03）")
    void 四项必填不可为空(String column) {
        long id = register(nextId());

        assertThatThrownBy(() -> insert(id, "OW9" + id,
            "ownerName".equals(column) ? null : "必填校验货主",
            "contactName".equals(column) ? null : "张三",
            "contactPhone".equals(column) ? null : "021-62345678",
            "creditCode".equals(column) ? null : uniqueCreditCode()))
            .as("字段 %s 的 not null 必须由库层兜住", column)
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(dbColumnOf(column));

        assertThat(countById(id)).as("被拒的插入不留行").isZero();
    }

    @ParameterizedTest(name = "{0} = {1} 被库层拒绝")
    @CsvSource({
        "owner_name,       '   '",
        "contact_name,     '   '",
        "contact_phone,    'abcdef'",
        "contact_phone,    '12345'",
        "credit_code,      '91350100ma2y1234xy'",
        "credit_code,      '91350100MA2Y1234X'",
    })
    @DisplayName("形态闭集：空串、字符集与长度越界在库层被拒绝（OI-03）")
    void 字段形态由库层兜住(String column, String value) {
        long id = register(nextId());
        String sql = "insert into wms_owner (id, owner_no, owner_name, contact_name, contact_phone,"
            + " credit_code, status, status_action, status_reason, status_changed_at,"
            + " status_changed_by, version, created_at, created_by, updated_at, updated_by)"
            + " values (?, ?, ?, ?, ?, ?, 'ACTIVE', 'CREATE', null, ?, 0, 0, ?, 0, ?, 0)";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThatThrownBy(() -> jdbc.update(sql, id, "OW9" + id,
            "owner_name".equals(column) ? value : "形态校验货主",
            "contact_name".equals(column) ? value : "张三",
            "contact_phone".equals(column) ? value : "021-62345678",
            "credit_code".equals(column) ? value : uniqueCreditCode(), now, now, now))
            .as("列 %s 的 CHECK 必须挡住 %s", column, value)
            .isInstanceOf(DataAccessException.class);

        assertThat(countById(id)).isZero();
    }

    @Test
    @DisplayName("状态与动作是闭集：闭集外的取值在库层被拒绝（OI-02、`21` E-18／E-19）")
    void 状态与动作闭集() {
        // 用 INSERT 验闭集：更新路径会先被状态守卫触发器拦下（那是另一条不变量，
        // 由「只放行两条状态迁移」覆盖），闭集本身要单独证明，否则两条手段会互相遮盖。
        assertThatThrownBy(() -> insertWith("PENDING", "CREATE"))
            .as("状态只有 ACTIVE 与 DISABLED 两个取值")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_wms_owner_status");

        assertThatThrownBy(() -> insertWith("ACTIVE", "SUSPEND"))
            .as("状态变更动作只有 CREATE／DISABLE／ENABLE 三个取值")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_wms_owner_status_action");
    }

    @Test
    @DisplayName("状态动作与原因绑死：CREATE 必空、DISABLE／ENABLE 必填（OI-02、spec「停用必须填写原因」）")
    void 动作与原因绑死() {
        long id = register(nextId());
        insert(id, "OW9" + id, "原因绑死货主", "张三", "021-62345678", uniqueCreditCode());

        assertThatThrownBy(() -> jdbc.update(
            "update wms_owner set status = 'DISABLED', status_action = 'DISABLE',"
                + " status_changed_at = now(), version = version + 1 where id = ?", id))
            .as("停用不带原因必须被库层拒绝")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_wms_owner_reason");

        assertThatThrownBy(() -> jdbc.update(
            "update wms_owner set status_reason = '建档不该有原因', version = version + 1"
                + " where id = ?", id))
            .as("建档动作下原因必须为空")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_wms_owner_reason");
    }

    @Test
    @DisplayName("货主编号唯一（OI-01）")
    void 编号唯一() {
        long first = register(nextId());
        String ownerNo = "OW8" + first;
        insert(first, ownerNo, "编号唯一货主 A", "张三", "021-62345678", uniqueCreditCode());

        long second = register(nextId());
        assertThatThrownBy(() -> insert(second, ownerNo, "编号唯一货主 B", "李四", "021-62345679",
            uniqueCreditCode()))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("uk_wms_owner_no");
    }

    @Test
    @DisplayName("信用代码唯一：停用档继续占位，同一法人不得再建第二条档案（OI-04）")
    void 信用代码唯一且停用档占位() {
        String creditCode = uniqueCreditCode();
        long active = register(nextId());
        String ownerNo = "OW8" + active;
        insert(active, ownerNo, "信用代码货主 A", "张三", "021-62345678", creditCode);

        long sameCode = register(nextId());
        assertThatThrownBy(() -> insert(sameCode, "OW8" + sameCode, "信用代码货主 B", "李四",
            "021-62345679", creditCode))
            .as("同码第二条档案被唯一索引挡住")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("uk_wms_owner_credit_code");

        disableBySql(active);

        long afterDisable = register(nextId());
        assertThatThrownBy(() -> insert(afterDisable, "OW8" + afterDisable, "信用代码货主 C", "王五",
            "021-62345670", creditCode))
            .as("停用档照样占位：停用不等于释放信用代码")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("uk_wms_owner_credit_code");
    }

    @Test
    @DisplayName("名称允许重复：wms_owner 上不存在含 owner_name 的唯一索引（OI-05 反证式约束）")
    void 名称可重复() {
        String ownerName = "同名货主-" + nextId();
        long first = register(nextId());
        long second = register(nextId());

        insert(first, "OW8" + first, ownerName, "张三", "021-62345678", uniqueCreditCode());
        insert(second, "OW8" + second, ownerName, "李四", "021-62345679", uniqueCreditCode());

        assertThat(countByName(ownerName)).as("同名两条档案可共存，靠货主编号区分").isEqualTo(2);
        assertThat(jdbc.queryForList("""
            select indexname
              from pg_indexes
             where tablename = 'wms_owner'
               and indexdef like '%UNIQUE%'
               and indexdef like '%owner_name%'
            """, String.class))
            .as("名称不作为任何唯一性条件，任何含 owner_name 的唯一索引都是违规")
            .isEmpty();
    }

    @Test
    @DisplayName("货主编号与主键创建后不可变：绕过应用改任一项都被触发器拒绝（OI-01）")
    void 编号与主键不可变() {
        long id = register(nextId());
        String ownerNo = "OW8" + id;
        insert(id, ownerNo, "不可变货主", "张三", "021-62345678", uniqueCreditCode());

        assertThatThrownBy(() -> jdbc.update("update wms_owner set owner_no = ? where id = ?",
            "OW7" + id, id))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("货主主键与货主编号创建后不可变更");
        assertThatThrownBy(() -> jdbc.update("update wms_owner set id = ? where id = ?",
            id + 1, id))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("货主主键与货主编号创建后不可变更");

        assertThat(jdbc.queryForObject("select owner_no from wms_owner where id = ?", String.class, id))
            .as("被拒的更新不留痕").isEqualTo(ownerNo);
    }

    @ParameterizedTest(name = "{0} → {1}（action={2}）被拒绝")
    @CsvSource({
        // 只放行 可用→停用(DISABLE) 与 停用→可用(ENABLE)；其余状态变化组合一律拒绝
        "ACTIVE,   DISABLED, CREATE",
        "ACTIVE,   DISABLED, ENABLE",
        "DISABLED, ACTIVE,   DISABLE",
        "DISABLED, ACTIVE,   CREATE",
    })
    @DisplayName("库层只放行两条状态迁移（OI-02、OI-12）")
    void 只放行两条状态迁移(String from, String to, String action) {
        long id = register(nextId());
        insert(id, "OW8" + id, "迁移校验货主", "张三", "021-62345678", uniqueCreditCode());
        if ("DISABLED".equals(from)) {
            disableBySql(id);
        }

        assertThatThrownBy(() -> jdbc.update(
            "update wms_owner set status = ?, status_action = ?, status_reason = ?,"
                + " status_changed_at = now(), version = version + 1 where id = ?",
            to, action, "CREATE".equals(action) ? null : "越过守卫的迁移", id))
            .as("%s → %s 只允许由 DISABLE／ENABLE 动作造成", from, to)
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("货主状态只允许");

        assertThat(jdbc.queryForObject("select status from wms_owner where id = ?", String.class, id))
            .as("被拒的迁移不改状态").isEqualTo(from);
    }

    @Test
    @DisplayName("两条合法状态迁移可被库层放行：可用→停用(DISABLE)、停用→可用(ENABLE)")
    void 合法迁移被放行() {
        long id = register(nextId());
        insert(id, "OW8" + id, "合法迁移货主", "张三", "021-62345678", uniqueCreditCode());

        disableBySql(id);
        assertThat(jdbc.queryForObject("select status_action from wms_owner where id = ?",
            String.class, id)).isEqualTo("DISABLE");

        jdbc.update("update wms_owner set status = 'ACTIVE', status_action = 'ENABLE',"
            + " status_reason = '合同续签', status_changed_at = now(), version = version + 1"
            + " where id = ?", id);
        assertThat(jdbc.queryForObject("select status from wms_owner where id = ?", String.class, id))
            .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("版本号必须逐次递增一：跳过或原地不动都被触发器拒绝（OI-09）")
    void 版本逐次递增() {
        long id = register(nextId());
        insert(id, "OW8" + id, "版本校验货主", "张三", "021-62345678", uniqueCreditCode());

        assertThatThrownBy(() -> jdbc.update(
            "update wms_owner set owner_name = '跳版本', version = version + 2 where id = ?", id))
            .as("一次前进两步必须被拒绝").isInstanceOf(DataAccessException.class)
            .hasMessageContaining("货主档案版本号必须逐次递增一");
        assertThatThrownBy(() -> jdbc.update(
            "update wms_owner set owner_name = '原地不动' where id = ?", id))
            .as("版本不前进必须被拒绝").isInstanceOf(DataAccessException.class)
            .hasMessageContaining("货主档案版本号必须逐次递增一");

        assertThat(jdbc.queryForObject("select version from wms_owner where id = ?", Long.class, id))
            .isZero();
    }

    @Test
    @DisplayName("不物理删除：仓储不提供删除、迁移脚本无删除语句、PUBLIC 无删除权限、拒绝删除的触发器在位（INV-18、OI-06）")
    void 不物理删除() throws IOException {
        assertThat(OwnerArchiveRepository.class.getDeclaredMethods())
            .extracting(Method::getName)
            .as("仓储接口不提供任何删除入口")
            .noneMatch(name -> name.toLowerCase().contains("delete")
                || name.toLowerCase().contains("remove"));

        Pattern deleteOwner = Pattern.compile(
            "(?i)(delete\\s+from\\s+wms_owner|truncate\\s+(table\\s+)?wms_owner)");
        List<String> offenders = new ArrayList<>();
        for (Path script : migrationScripts()) {
            if (deleteOwner.matcher(executableSql(script)).find()) {
                offenders.add(script.getFileName().toString());
            }
        }
        assertThat(offenders).as("迁移脚本内不得出现删除货主档案的语句").isEmpty();

        // 库层第一层：revoke 只对非属主角色生效，核对「没被反向授予」。
        assertThat(jdbc.queryForList("""
            select privilege_type
              from information_schema.table_privileges
             where table_name = 'wms_owner'
               and grantee = 'PUBLIC'
               and privilege_type in ('DELETE', 'TRUNCATE')
            """, String.class))
            .as("INV-18：删除与清空不得授予 PUBLIC").isEmpty();

        // 库层第二层：拒绝删除的触发器（V20261143）。它挡的是「有 delete 权限的人」，含表属主，
        // 也就是迁移账号自己——revoke 对属主无效，这一层才是不受部署账号影响的兜底。
        assertThat(jdbc.queryForList("""
            select tgname
              from pg_trigger
             where tgrelid = 'wms_owner'::regclass
               and not tgisinternal
               and tgname = 'trg_wms_owner_no_delete'
            """, String.class))
            .as("INV-18：wms_owner 上必须有拒绝删除的 BEFORE DELETE 触发器").containsExactly(
                "trg_wms_owner_no_delete");

        // 库层第三层：拒绝 TRUNCATE 的语句级触发器（V20261144）。行级触发器挡不住表级清空，
        // 缺这一层时属主一条 `truncate` 就能把 INV-18 绕过去。
        assertThat(jdbc.queryForList("""
            select tgname
              from pg_trigger
             where tgrelid = 'wms_owner'::regclass
               and not tgisinternal
               and tgname = 'trg_wms_owner_no_truncate'
            """, String.class))
            .as("INV-18：wms_owner 上必须有拒绝 TRUNCATE 的 BEFORE TRUNCATE 触发器").containsExactly(
                "trg_wms_owner_no_truncate");
    }

    /**
     * TRUNCATE 的实际效果：与迁移同账号（表属主）执行也必须被拒。
     *
     * <p>与删除用例分开的原因同 {@link #删除被触发器拒绝()}：那条证明「按行删不掉」，
     * 这条证明「整表清不掉」。TRUNCATE 不走行级触发器，是 V20261143 之后仍存在的缺口。
     *
     * <p>语句包在事务里并强制回滚：万一触发器失效，本用例只是断言失败，不会把表清空
     * ——测试不能为了证明「挡得住」而先真的毁掉数据。
     */
    @Test
    @DisplayName("TRUNCATE 被库层拒绝：与迁移同账号（表属主）执行 truncate 也抛错（INV-18、OI-06）")
    void 清空被触发器拒绝() {
        long id = register(nextId());
        insert(id, "OW6" + id, "不接受清空的货主", "张三", "021-62345678", uniqueCreditCode());

        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(
            transactionManager).executeWithoutResult(status -> {
                try {
                    jdbc.update("truncate table wms_owner");
                } finally {
                    // 无论如何回滚：触发器失效时 truncate 已执行，必须撤回，否则本用例会毁掉整个库。
                    status.setRollbackOnly();
                }
            }))
            .as("INV-18：整表清空由语句级 BEFORE TRUNCATE 触发器拒绝，属主也绕不过")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("货主档案不允许 truncate");

        assertThat(countById(id)).as("被拒的清空不改变行数").isOne();
    }

    /**
     * 触发器的实际效果：以当前连接（与迁移同一部署账号，即表属主）删一行也必须被拒。
     *
     * <p>为什么不与 {@link #不物理删除()} 合并：那条断言的是「手段存在」（脚本、授权、触发器元数据），
     * 这条断言的是「手段生效」。只有真的执行一次删除才知道 revoke 之外的那一层是否真的挡得住属主
     * ——F-6 的原始缺口正是「元数据看起来有手段、实际删得掉」。
     *
     * <p>被删的行留着不动（删除本来就被拒），用例只读它一次，不参与自清。
     */
    @Test
    @DisplayName("删除被库层拒绝：与迁移同账号（表属主）执行 delete 也抛错，行仍在（INV-18、OI-06）")
    void 删除被触发器拒绝() {
        long id = register(nextId());
        insert(id, "OW7" + id, "不物理删除货主", "张三", "021-62345678", uniqueCreditCode());

        assertThatThrownBy(() -> jdbc.update("delete from wms_owner where id = ?", id))
            .as("INV-18：行级删除由 BEFORE DELETE 触发器拒绝，属主也绕不过")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("货主档案不允许物理删除");

        assertThat(countById(id)).as("被拒的删除不改变行数：数据靠状态表达停用").isOne();
    }

    @Test
    @DisplayName("第二窗口未执行：wms_account.owner_id 尚无外键，约束定义留在运维脚本且不在自动迁移链路")
    void 归属货主外键仍在第二窗口() throws IOException {
        boolean exists = foreignKeyExists("wms_account", "fk_wms_account_owner");

        // 两个分支各断言真实事实：窗口未执行时「约束只在运维脚本里」，窗口执行后「约束定义正确」。
        // 不写死 exists=false：那一版断言在第二窗口执行后必然假红（与 OwnerArchiveWindowOneIT 同一口径）。
        if (!exists) {
            assertThat(Files.exists(OPS_SCRIPT))
                .as("第二窗口脚本必须留在 %s，外键的定义在那里", OPS_SCRIPT).isTrue();
            String script = Files.readString(OPS_SCRIPT, StandardCharsets.UTF_8);
            assertThat(script)
                .as("窗口未执行时，外键只由运维脚本声明")
                .contains("add constraint fk_wms_account_owner foreign key (owner_id)")
                .contains("references wms_owner (id)")
                .contains("on update restrict on delete restrict");
        } else {
            assertThat(jdbc.queryForList("""
                select confdeltype::text || confupdtype::text
                  from pg_constraint
                 where conname = 'fk_wms_account_owner'
                """, String.class))
                .as("外键必须是 restrict/restrict（restrict 的代码是 r）")
                .containsExactly("rr");
        }

        // 无论窗口状态如何，这个外键都不允许出现在自动迁移目录里：
        // 放进 Flyway 后，补录清单未清的环境一启动就中止（design「为什么第三步不进 Flyway 目录」）。
        assertThat(new ClassPathResource(MIGRATION_DIR + "V20261138__hub_owner_import_and_foreign_keys.sql")
            .exists())
            .as("第二窗口脚本不得回到自动迁移目录").isFalse();

        Pattern addsAccountFk = Pattern.compile(
            "(?i)add constraint\\s+fk_wms_account_owner");
        List<String> offenders = new ArrayList<>();
        for (Path script : migrationScripts()) {
            if (addsAccountFk.matcher(Files.readString(script, StandardCharsets.UTF_8)).find()) {
                offenders.add(script.getFileName().toString());
            }
        }
        assertThat(offenders).as("自动迁移目录里不得有建该外键的脚本").isEmpty();
    }

    /** 插入一条形态合法的档案行（16 列齐备），不做任何业务校验。 */
    private void insert(Long id, String ownerNo, String ownerName, String contactName,
                        String contactPhone, String creditCode) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into wms_owner (id, owner_no, owner_name, contact_name, contact_phone, credit_code,
                status, status_action, status_reason, status_changed_at, status_changed_by, version,
                created_at, created_by, updated_at, updated_by)
            values (?, ?, ?, ?, ?, ?, 'ACTIVE', 'CREATE', null, ?, 0, 0, ?, 0, ?, 0)
            """, id, ownerNo, ownerName, contactName, contactPhone, creditCode, now, now, now);
    }

    /**
     * 以给定的状态与动作插一行：用于证明闭集 CHECK（INSERT 路径不经过状态守卫触发器）。
     *
     * <p>原因按动作补齐（非 {@code CREATE} 必须有原因），让「原因绑死」那条 CHECK 不参与本次判定——
     * 否则同一行会同时违反两条约束，报错里出现哪一条由 PostgreSQL 决定，断言就不稳定了。
     */
    private void insertWith(String status, String action) {
        long id = register(nextId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into wms_owner (id, owner_no, owner_name, contact_name, contact_phone, credit_code,
                status, status_action, status_reason, status_changed_at, status_changed_by, version,
                created_at, created_by, updated_at, updated_by)
            values (?, ?, '闭集校验货主', '张三', '021-62345678', ?, ?, ?, ?, ?, 0, 0, ?, 0, ?, 0)
            """, id, "OW9" + id, uniqueCreditCode(), status, action,
            "CREATE".equals(action) ? null : "闭集校验", now, now, now);
    }

    /** 按库层允许的唯一一条路径停用：状态、动作、原因与版本同一次写入。 */
    private void disableBySql(long id) {
        jdbc.update("update wms_owner set status = 'DISABLED', status_action = 'DISABLE',"
            + " status_reason = '用例前置：停用', status_changed_at = now(), version = version + 1"
            + " where id = ?", id);
    }

    private long register(long id) {
        rows.add(id);
        return id;
    }

    private int countById(long id) {
        return jdbc.queryForObject("select count(*) from wms_owner where id = ?", Integer.class, id);
    }

    private int countByName(String ownerName) {
        return jdbc.queryForObject("select count(*) from wms_owner where owner_name = ?",
            Integer.class, ownerName);
    }

    private boolean foreignKeyExists(String table, String constraint) {
        return jdbc.queryForObject("""
            select count(*)
              from pg_constraint c
              join pg_class t on t.oid = c.conrelid
             where t.relname = ? and c.conname = ? and c.contype = 'f'
            """, Integer.class, table, constraint) > 0;
    }

    /**
     * 脚本里可执行的那部分：剔掉整行注释（{@code --}）与块注释（{@code /* *}{@code /}）。
     *
     * <p>注释不是语句，执行不了。{@code V20261143} 的注释里引用了 F-6 的实证——缺口存在时
     * 测试清理里那条按主键的 DELETE 每次都成功——把这种「记录缺口」的示例 SQL 也算成违规，
     * 会逼着人删掉证据或者改写注释，判定力反而下降。变异验证不受影响：
     * 「往迁移目录放一条真的删除语句」依然会被下面的正则逮住。
     */
    private static String executableSql(Path script) throws IOException {
        String source = Files.readString(script, StandardCharsets.UTF_8)
            .replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder executable = new StringBuilder();
        for (String line : source.split("\\R")) {
            if (!line.stripLeading().startsWith("--")) {
                executable.append(line).append('\n');
            }
        }
        return executable.toString();
    }

    /** 自动迁移目录里的全部脚本：文件系统与 classpath 都取，任一侧出现违规都要红。 */
    private List<Path> migrationScripts() throws IOException {
        List<Path> scripts = new ArrayList<>();
        if (Files.isDirectory(MIGRATION_SOURCE_DIR)) {
            try (var stream = Files.walk(MIGRATION_SOURCE_DIR)) {
                stream.filter(path -> path.toString().endsWith(".sql")).forEach(scripts::add);
            }
        }
        Resource[] classpathResources = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:" + MIGRATION_DIR + "*.sql");
        for (Resource resource : classpathResources) {
            scripts.add(Path.of(resource.getURI()));
        }
        assertThat(scripts).as("必须能找到迁移脚本，否则本断言会假绿").isNotEmpty();
        return scripts;
    }

    private static String dbColumnOf(String field) {
        return switch (field) {
            case "ownerName" -> "owner_name";
            case "contactName" -> "contact_name";
            case "contactPhone" -> "contact_phone";
            case "creditCode" -> "credit_code";
            default -> throw new IllegalArgumentException("未知字段：" + field);
        };
    }

    /** 18 位信用代码：前两位固定 91，其余取本进程造数标识，落在 {@code ^[0-9]{18}$} 内。 */
    private static String uniqueCreditCode() {
        return "91" + String.format("%016d", Math.floorMod(nextId(), 10_000_000_000_000_000L));
    }
}
