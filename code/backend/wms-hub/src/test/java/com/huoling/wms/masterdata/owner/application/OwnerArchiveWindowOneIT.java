package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.application.SkuArchiveQueryService;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.owner.domain.OwnerArchive;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveRepository;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第一发布窗口的中间态（tasks 1.6）：只有 {@code V20261136} 与 {@code V20261137} 进了自动迁移。
 *
 * <p>设计口径（design「发布批次」与「为什么第三步不进 Flyway 目录」）：第一窗口随应用发布自动执行
 * 建表与补录候选清单；补录落库与外键是第二窗口的运维脚本（{@code code/db/ops/}），由发布负责人
 * 手工执行。中间态下 {@code wms_account.owner_id} 与 {@code hub_sku_archive.owner_id} 没有外键，
 * 但读写不受影响，应用必须能正常启动、建档与查询。
 *
 * <p>本类用真实库表达这条验证，三段断言各管一件事：
 * <ul>
 *   <li>迁移链路：{@code flyway_schema_history} 里有 1136、1137，没有 1138、1139，
 *       且自动迁移目录里不存在这三份第二窗口脚本——这是「启动不会被补录清单卡住」的根因；
 *   <li>建 档：{@code wms_owner} 可写可读，数据域在无外键时仍是唯一一道门（OI-08、INV-19）；
 *   <li>查 询：商品档案的写与查在货主外键缺位时照常工作。
 * </ul>
 *
 * <p>为什么不直接断言「两列没有外键」：那是时间窗口内的状态，不是永久不变量；第二窗口执行后
 * 该断言会假红。因此本类把窗口状态表达为「Flyway 历史里没有这两个版本 + 自动迁移目录里没有
 * 这两个文件」，它在外键补上之后依然成立，且能挡住第二窗口脚本被误放回自动迁移目录。
 */
@SpringBootTest(classes = HubApplication.class)
class OwnerArchiveWindowOneIT extends ArchiveDbTestSupport {

    /** 自动迁移目录在 classpath 上的位置，与 application.yml 的 flyway.locations 一致。 */
    private static final String MIGRATION_DIR = "db/migration/";

    /** 迁移账号：脚本与测试都用 0 表示非人操作（wms_owner.created_by = 0）。 */
    private static final long MIGRATION_OPERATOR = 0L;

    @Autowired
    private OwnerCommandService ownerCommandService;

    @Autowired
    private OwnerArchiveRepository ownerArchiveRepository;

    /** 本类建出来的货主档案主键：只用于清理引用它的商品档案行。 */
    private final Set<Long> createdOwnerIds = new LinkedHashSet<>();

    /**
     * 只删引用方（商品档案行），货主档案行本身保留。
     *
     * <p>第二窗口把外键补上之后，先删货主会被 {@code fk_hub_sku_archive_owner} 的
     * {@code on delete restrict} 挡住；而货主档案行现在也删不掉——{@code V20261143} 的
     * {@code before delete} 触发器对属主同样生效（INV-18）。残留行按
     * {@code nextId()} 的每次运行不同基址与唯一信用代码区分，不影响本类与其他用例的断言。
     */
    @AfterEach
    void cleanUpOwnerArchives() {
        for (Long ownerId : createdOwnerIds) {
            jdbc.update("delete from hub_sku_archive where owner_id = ?", ownerId);
        }
        createdOwnerIds.clear();
    }

    @Test
    @DisplayName("第一窗口只自动执行建表与候选清单，补录与外键脚本不在自动迁移链路里")
    void 第一窗口迁移已执行且第二窗口脚本不在自动迁移链路() {
        List<String> applied = jdbc.queryForList(
            "select version from flyway_schema_history where success and version is not null "
                + "order by installed_rank", String.class);

        assertThat(applied)
            .as("货主档案表与补录候选清单必须随应用发布自动执行")
            .contains("20261136", "20261137");
        assertThat(applied)
            .as("补录落库与外键是第二窗口的运维脚本，不得出现在 Flyway 历史里")
            .doesNotContain("20261138", "20261139");

        assertThat(new ClassPathResource(MIGRATION_DIR + "V20261138__hub_owner_import_and_foreign_keys.sql")
            .exists())
            .as("合并后的第二窗口脚本必须留在 code/db/ops/，自动迁移目录里不能有它")
            .isFalse();
        assertThat(new ClassPathResource(MIGRATION_DIR + "V20261138__hub_owner_import_apply.sql").exists())
            .as("旧的补录写入脚本必须移出自动迁移目录，否则补录未清时任何环境启动即中止")
            .isFalse();
        assertThat(new ClassPathResource(MIGRATION_DIR + "V20261139__hub_owner_foreign_keys.sql").exists())
            .as("旧的独立外键脚本必须移出自动迁移目录，它已并入 code/db/ops/ 的运维脚本")
            .isFalse();
    }

    @Test
    @DisplayName("第一窗口可建档并可读回，数据域在无外键时仍是唯一一道门")
    void 第一窗口可建档并可读回() {
        String creditCode = uniqueCreditCode();
        OwnerFields fields = new OwnerFields("窗口一货主-" + nextId(), "张三", "13800000000", creditCode);

        OwnerCommandService.OwnerResultView created =
            ownerCommandService.create(OwnerScope.all(), fields, MIGRATION_OPERATOR);
        createdOwnerIds.add(Long.valueOf(created.ownerId()));

        assertThat(created.ownerNo()).as("编号由序列生成，形如 OW000001").matches("^OW[A-Z0-9]{6,30}$");
        assertThat(created.status()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(created.version()).isZero();

        Optional<OwnerArchive> loaded =
            ownerArchiveRepository.findByOwnerNo(created.ownerNo(), OwnerScope.all());
        assertThat(loaded).as("建档后必须能按编号读回").isPresent();
        assertThat(loaded.orElseThrow().fields()).as("四项原样落库").isEqualTo(fields);

        // 中间态没有外键兜底，数据域是唯一一道门：域外按不存在处理，不泄露存在性（OI-08、INV-19）
        assertThat(ownerArchiveRepository.findByOwnerNo(created.ownerNo(), OwnerScope.of(Set.of(nextId()))))
            .as("域外查询按不存在处理")
            .isEmpty();
    }

    @Test
    @DisplayName("第一窗口商品档案的写入与查询在货主外键缺位时照常工作")
    void 第一窗口商品档案读写不受货主外键影响() {
        OwnerCommandService.OwnerResultView owner = ownerCommandService.create(OwnerScope.all(),
            new OwnerFields("窗口一商品货主-" + nextId(), "李四", "13900000000", uniqueCreditCode()),
            MIGRATION_OPERATOR);
        long ownerId = Long.parseLong(owner.ownerId());
        createdOwnerIds.add(ownerId);

        String ownerCode = "W1-" + nextId();
        commandService.createDraft(ownerId,
            fields(ownerCode, "690" + String.format("%010d", Math.floorMod(nextId(), 10_000_000_000L)),
                "窗口一商品"),
            MIGRATION_OPERATOR);

        SkuArchiveQueryService.ArchivePage page = queryService.pageForOwner(ownerId, null, null, null,
            null, null, null, SkuListSort.parse(null, null), null, 20);

        assertThat(page.items()).extracting(item -> item.fields().ownerCode()).contains(ownerCode);
        assertThat(page.dataTimestamp()).as("列表必须回出数据时间戳，降级查询依赖它").isNotNull();
    }

    /**
     * 信用代码：18 位、只含 CHECK 允许的字符（数字与大写字母，排除 I／O／S／V／Z）。
     *
     * <p>前两位固定 91，其余取本进程内的造数标识并左补零——同一次运行内天然唯一，
     * 不会撞 {@code uk_wms_owner_credit_code}。
     */
    private static String uniqueCreditCode() {
        return "91" + String.format("%016d", Math.floorMod(nextId(), 10_000_000_000_000_000L));
    }
}
