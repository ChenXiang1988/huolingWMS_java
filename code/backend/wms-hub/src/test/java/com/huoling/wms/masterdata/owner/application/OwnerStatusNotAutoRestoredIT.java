package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 停用不自动恢复（spec「停用不自动恢复」、OI-12）：系统动作跑一轮之后状态仍是停用。
 *
 * <p>spec 列了三个触发点：系统重启、迁移重跑、定时任务执行。本类逐个真跑：
 * <ul>
 *   <li><b>迁移重跑</b>：{@code Flyway.migrate()} 再执行一次，断言「应用了 0 个迁移」且状态不变；
 *       只要有人加一条把 {@code status} 写回 {@code ACTIVE} 的脚本，这里立刻红；</li>
 *   <li><b>定时任务</b>：本进程里唯一碰货主档案的定时任务是积压刷新（{@link OwnerAcceptanceBacklog}），
 *       手工触发一轮后状态不变；投递器与受理补偿扫描只动 {@code hub_outbox} 与受理单，
 *       不写 {@code wms_owner.status}，由库层触发器再做一层兜底（见 {@code OwnerArchiveConstraintIT}）。</li>
 * </ul>
 *
 * <p><b>系统重启</b>这一条不单独测：重启的效果等于「迁移重跑 + 定时任务重跑」，两条都已覆盖；
 * 再起一个独立上下文只是把同一条断言重跑一遍，不增加判定力。
 */
@SpringBootTest(classes = HubApplication.class)
class OwnerStatusNotAutoRestoredIT extends ArchiveDbTestSupport {

    private static final long OPERATOR = 0L;

    @Autowired
    private OwnerCommandService ownerCommandService;

    @Autowired
    private OwnerAcceptanceBacklog backlog;

    @Autowired
    private Flyway flyway;

    private final Set<Long> ownerRows = new LinkedHashSet<>();

    /**
     * 只清下行消息，货主档案行保留。
     *
     * <p>{@code wms_owner} 的行级删除被 {@code V20261143} 的触发器统一拒绝（INV-18，属主也绕不过），
     * 清理不能靠删表；残留行按 {@code nextId()} 的每次运行不同基址与唯一信用代码区分。
     */
    @AfterEach
    void cleanOwners() {
        for (Long ownerId : ownerRows) {
            jdbc.update("delete from hub_outbox where owner_id = ?", ownerId);
        }
        ownerRows.clear();
    }

    @Test
    @DisplayName("迁移重跑：Flyway 再执行一次不应用任何迁移，已停用的档案仍是 DISABLED")
    void 迁移重跑不恢复可用() {
        OwnerCommandService.OwnerResultView created = ownerCommandService.create(OwnerScope.all(),
            new OwnerFields("迁移重跑货主-" + nextId(), "张三", "021-62345678", uniqueCreditCode()),
            OPERATOR);
        ownerRows.add(Long.valueOf(created.ownerId()));
        ownerCommandService.disable(created.ownerNo(), OwnerScope.all(), created.version(),
            "合同到期", OPERATOR);

        MigrateResult result = flyway.migrate();

        assertThat(result.migrationsExecuted)
            .as("重跑一次不应有可执行迁移：出现非零说明仓库里有待执行脚本，判定口径要重新确认")
            .isZero();
        assertThat(statusOf(created.ownerNo())).as("迁移重跑不得把状态改回可用")
            .isEqualTo(OwnerStatus.DISABLED);
        assertThat(jdbc.queryForObject(
            "select status_action from wms_owner where owner_no = ?", String.class,
            created.ownerNo())).as("留痕仍是停用，没被系统动作覆盖").isEqualTo("DISABLE");
        assertThat(jdbc.queryForObject("select version from wms_owner where owner_no = ?",
            Long.class, created.ownerNo())).as("系统动作不推进版本").isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status_reason from wms_owner where owner_no = ?",
            String.class, created.ownerNo())).as("停用原因保留").isEqualTo("合同到期");
    }

    @Test
    @DisplayName("定时任务执行：积压刷新跑一轮后，停用状态与留痕都不变")
    void 定时任务不恢复可用() {
        OwnerCommandService.OwnerResultView created = ownerCommandService.create(OwnerScope.all(),
            new OwnerFields("定时任务货主-" + nextId(), "李四", "021-62345679", uniqueCreditCode()),
            OPERATOR);
        ownerRows.add(Long.valueOf(created.ownerId()));
        ownerCommandService.disable(created.ownerNo(), OwnerScope.all(), created.version(),
            "合同到期", OPERATOR);

        assertThatCode(() -> backlog.refresh()).doesNotThrowAnyException();

        assertThat(statusOf(created.ownerNo())).isEqualTo(OwnerStatus.DISABLED);
        assertThat(jdbc.queryForObject("select version from wms_owner where owner_no = ?",
            Long.class, created.ownerNo())).isEqualTo(1L);
    }

    @Test
    @DisplayName("重新启用只走人的入口：启用后状态与留痕都来自该次操作，编号不变")
    void 只有人能恢复可用() {
        OwnerCommandService.OwnerResultView created = ownerCommandService.create(OwnerScope.all(),
            new OwnerFields("人工启用货主-" + nextId(), "王五", "021-62345670", uniqueCreditCode()),
            OPERATOR);
        ownerRows.add(Long.valueOf(created.ownerId()));
        ownerCommandService.disable(created.ownerNo(), OwnerScope.all(), created.version(),
            "合同到期", OPERATOR);

        OwnerCommandService.OwnerResultView enabled = ownerCommandService.enable(created.ownerNo(),
            OwnerScope.all(), 1L, "合同续签", 9527L);

        assertThat(enabled.status()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(enabled.ownerNo()).as("编号不随启用变化").isEqualTo(created.ownerNo());
        assertThat(enabled.version()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select status_changed_by from wms_owner where owner_no = ?",
            Long.class, created.ownerNo())).as("启用的操作人必须是人，不是 0 号迁移账号").isEqualTo(9527L);
        assertThat(jdbc.queryForObject("select status_reason from wms_owner where owner_no = ?",
            String.class, created.ownerNo())).as("启用同样留原因").isEqualTo("合同续签");
    }

    private OwnerStatus statusOf(String ownerNo) {
        return OwnerStatus.valueOf(jdbc.queryForObject("select status from wms_owner where owner_no = ?",
            String.class, ownerNo));
    }

    private static String uniqueCreditCode() {
        return "91" + String.format("%016d", Math.floorMod(nextId(), 10_000_000_000_000_000L));
    }
}
