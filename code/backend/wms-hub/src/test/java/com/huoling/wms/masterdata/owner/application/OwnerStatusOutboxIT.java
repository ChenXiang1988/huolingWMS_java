package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusChangedEvent;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 货主状态变更与 {@code hub_outbox} 同事务落库（tasks 1.13；design T-07、契约 1.9）。
 *
 * <p>连真库验四件事：建档、停用、启用各写一条消息且事件标识为「编号 + 版本号」；
 * 改档不写；载荷不带手机号与信用代码；写入与业务状态在同一事务里（业务回滚则消息不落库）。
 */
@SpringBootTest(classes = com.huoling.wms.hub.HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerStatusOutboxIT extends OwnerApiTestSupport {

    private static final String PHONE = "021-62345678";

    private final Set<Long> outboxOwners = new LinkedHashSet<>();

    /** 消息表按 {@code owner_id} 清理：父类只清账号与档案行，消息表由本类负责。 */
    @AfterEach
    void cleanOutbox() {
        for (Long ownerId : outboxOwners) {
            jdbc.update("delete from hub_outbox where owner_id = ?", ownerId);
        }
        outboxOwners.clear();
    }

    @Test
    @DisplayName("建档、停用、启用各写一条状态事件；改档不写")
    void 状态变更写本地消息() {
        OwnerCommandService.OwnerResultView created =
            prepareOwner("华东仓储有限公司-" + nextId(), uniqueCreditCode());
        long ownerId = Long.parseLong(created.ownerId());
        outboxOwners.add(ownerId);

        assertThat(messages(ownerId)).as("建档即产生一条下行").hasSize(1);
        Map<String, Object> born = messages(ownerId).get(0);
        assertThat(born.get("event_id")).isEqualTo(created.ownerNo() + "#0");
        assertThat(born.get("event_type")).isEqualTo(OwnerStatusChangedEvent.TYPE);
        assertThat(born.get("aggregate_no")).isEqualTo(created.ownerNo());
        assertThat(born.get("version")).isEqualTo(0L);
        // 状态取闭集内的值即可：投递器是另一个定时任务，用例只断言「消息已按 T-07 落库」，
        // 不赌它在用例执行期间没被扫走（扫走只会把 PENDING 变成 SENT / FAILED）。
        assertThat(born.get("status")).isIn("PENDING", "SENT", "FAILED");
        assertThat((String) born.get("payload_json")).contains("\"status\":\"ACTIVE\"")
            .contains("\"action\":\"CREATE\"")
            .contains("\"ownerNo\":\"" + created.ownerNo() + "\"");

        ownerCommandService.edit(created.ownerNo(), OwnerScope.all(), 0L, fields("改个名字"),
            MIGRATION_OPERATOR);
        assertThat(messages(ownerId)).as("改档不发事件").hasSize(1);

        ownerCommandService.disable(created.ownerNo(), OwnerScope.all(), 1L, "合同到期",
            MIGRATION_OPERATOR);
        ownerCommandService.enable(created.ownerNo(), OwnerScope.all(), 2L, "合同续签",
            MIGRATION_OPERATOR);

        assertThat(messages(ownerId)).extracting(row -> row.get("event_id"))
            .containsExactly(created.ownerNo() + "#0", created.ownerNo() + "#2",
                created.ownerNo() + "#3");
        assertThat(messages(ownerId)).extracting(row -> row.get("payload_json"))
            .anySatisfy(json -> assertThat((String) json).contains("\"status\":\"DISABLED\""))
            .anySatisfy(json -> assertThat((String) json).contains("\"action\":\"ENABLE\""));
    }

    @Test
    @DisplayName("载荷不带手机号与信用代码等敏感字段")
    void 载荷不含敏感字段() {
        String creditCode = uniqueCreditCode();
        OwnerCommandService.OwnerResultView created =
            prepareOwner("载荷校验货主-" + nextId(), creditCode);
        long ownerId = Long.parseLong(created.ownerId());
        outboxOwners.add(ownerId);

        ownerCommandService.disable(created.ownerNo(), OwnerScope.all(), 0L, "合同到期",
            MIGRATION_OPERATOR);

        for (Map<String, Object> row : messages(ownerId)) {
            String payload = (String) row.get("payload_json");
            assertThat(payload).doesNotContain(PHONE).doesNotContain(creditCode)
                .doesNotContain("contactPhone").doesNotContain("creditCode");
        }
    }

    @Test
    @DisplayName("业务写入失败时消息不落库：状态与消息在同一事务")
    void 同事务() {
        OwnerCommandService.OwnerResultView created =
            prepareOwner("事务校验货主-" + nextId(), uniqueCreditCode());
        long ownerId = Long.parseLong(created.ownerId());
        outboxOwners.add(ownerId);
        int before = messages(ownerId).size();

        // 重复停用被状态机拒绝：状态没变，消息也不得新增。
        ownerCommandService.disable(created.ownerNo(), OwnerScope.all(), 0L, "第一次停用",
            MIGRATION_OPERATOR);
        int afterFirst = messages(ownerId).size();
        assertThat(afterFirst).isEqualTo(before + 1);
        assertThat(catchDisable(created.ownerNo())).isTrue();
        assertThat(messages(ownerId)).as("失败的业务动作不留下消息").hasSize(afterFirst);
    }

    private boolean catchDisable(String ownerNo) {
        try {
            ownerCommandService.disable(ownerNo, OwnerScope.all(), 1L, "重复停用",
                MIGRATION_OPERATOR);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private List<Map<String, Object>> messages(long ownerId) {
        return jdbc.queryForList("""
            select event_id, event_type, aggregate_no, version, owner_id, payload_json, status,
                   retry_count
              from hub_outbox
             where owner_id = ?
             order by created_at asc, version asc
            """, ownerId);
    }
}
