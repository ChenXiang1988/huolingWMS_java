package com.huoling.wms.site.application;

import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.site.domain.SiteException;
import com.huoling.wms.site.domain.SiteOwnerStatus;
import com.huoling.wms.site.domain.SiteOwnerStatusRepository;
import com.huoling.wms.site.support.SiteDbTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主状态下行消费（tasks 1.14；契约 1.9、design「停用货主如何阻断单仓端新建业务」）。
 *
 * <p>连真库：去重靠主键 upsert、乱序保护靠 {@code where excluded.source_version >= ...} 的条件更新，
 * 换成内存库这两条都验不出来。
 *
 * <p>覆盖：应用可用与停用、同一事件标识不产生第二行、事件乱序不覆盖、
 * 未知状态按参数错误抛出（由监听器转死信）、存量链路不经过闸门。
 */
@SpringBootTest(classes = com.huoling.wms.site.SiteApplication.class,
    properties = {"wms.site.id=1001"})
class OwnerStatusDownlinkIT extends SiteDbTestSupport {

    /** 权威数据的产生时间，刻意取过去时刻：消费时间必然晚于它。 */
    private static final Instant CHANGED_AT = Instant.parse("2026-01-05T08:00:00Z");

    @Autowired
    private OwnerStatusConsumer consumer;

    @Autowired
    private SiteOwnerStatusRepository statuses;

    @Test
    @DisplayName("货主状态落到本仓副本：状态、版本与数据时间戳取自消息而不是消费时间")
    void 应用货主状态() {
        long ownerId = registerOwnerStatus(nextId());
        String ownerNo = ownerNoOf(ownerId);

        boolean applied = consumer.consume(ownerNo + "#7",
            OwnerStatusConsumer.TYPE_OWNER_STATUS_CHANGED,
            ownerStatusPayload(ownerId, ownerNo, "DISABLED", 7, CHANGED_AT));

        assertThat(applied).isTrue();
        SiteOwnerStatus snapshot = statuses.find(ownerId).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(OwnerStatus.DISABLED);
        assertThat(snapshot.sourceVersion()).isEqualTo(7L);
        assertThat(snapshot.eventId()).isEqualTo(ownerNo + "#7");
        assertThat(snapshot.dataTimestamp()).isEqualTo(CHANGED_AT);
        assertThat(snapshot.updatedAt()).as("写入时间是消费时间，晚于权威数据时间")
            .isAfter(CHANGED_AT);
    }

    @Test
    @DisplayName("同一事件标识重复投递只更新同一行，不产生第二行")
    void 事件标识去重() {
        long ownerId = registerOwnerStatus(nextId());
        String ownerNo = ownerNoOf(ownerId);
        String payload = ownerStatusPayload(ownerId, ownerNo, "ACTIVE", 3, CHANGED_AT);

        assertThat(consumer.consume(ownerNo + "#3",
            OwnerStatusConsumer.TYPE_OWNER_STATUS_CHANGED, payload)).isTrue();
        assertThat(consumer.consume(ownerNo + "#3",
            OwnerStatusConsumer.TYPE_OWNER_STATUS_CHANGED, payload)).isTrue();

        assertThat(jdbc.queryForObject(
            "select count(*) from site_owner_status where owner_id = ?", Integer.class, ownerId))
            .as("重复投递不产生第二行").isEqualTo(1);
        assertThat(statuses.find(ownerId).orElseThrow().sourceVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("事件乱序：旧版本不覆盖新状态，副本状态与版本都不倒退")
    void 乱序事件不覆盖() {
        long ownerId = registerOwnerStatus(nextId());
        String ownerNo = ownerNoOf(ownerId);
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");

        assertThat(consumer.consume(ownerNo + "#5",
            OwnerStatusConsumer.TYPE_OWNER_STATUS_CHANGED,
            ownerStatusPayload(ownerId, ownerNo, "DISABLED", 5, newer))).isTrue();
        boolean applied = consumer.consume(ownerNo + "#2",
            OwnerStatusConsumer.TYPE_OWNER_STATUS_CHANGED,
            ownerStatusPayload(ownerId, ownerNo, "ACTIVE", 2, CHANGED_AT));

        assertThat(applied).as("旧版本被丢弃").isFalse();
        SiteOwnerStatus snapshot = statuses.find(ownerId).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(OwnerStatus.DISABLED);
        assertThat(snapshot.sourceVersion()).isEqualTo(5L);
        assertThat(snapshot.dataTimestamp()).isEqualTo(newer);
    }

    @Test
    @DisplayName("未知状态与缺时间戳按参数错误抛出，由监听器拒绝并转死信，不静默丢弃")
    void 非法摘要拒绝() {
        long ownerId = registerOwnerStatus(nextId());
        String ownerNo = ownerNoOf(ownerId);

        assertThatThrownBy(() -> consumer.consume(ownerNo + "#1",
            OwnerStatusConsumer.TYPE_OWNER_STATUS_CHANGED,
            ownerStatusPayload(ownerId, ownerNo, "SUSPENDED", 1, CHANGED_AT)))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo("PARAM_INVALID");

        assertThatThrownBy(() -> consumer.consume(ownerNo + "#1",
            OwnerStatusConsumer.TYPE_OWNER_STATUS_CHANGED,
            ownerStatusPayload(ownerId, ownerNo, "ACTIVE", 1, null)))
            .isInstanceOf(SiteException.class);

        assertThat(statuses.find(ownerId)).isEmpty();
    }

    @Test
    @DisplayName("非货主状态事件按未知类型丢弃，不写副本、也不抛异常")
    void 非本消费者事件丢弃() {
        long ownerId = registerOwnerStatus(nextId());
        String ownerNo = ownerNoOf(ownerId);

        assertThat(consumer.consume(ownerNo + "#1", "SKU_ARCHIVE_ACTIVATED",
            ownerStatusPayload(ownerId, ownerNo, "DISABLED", 1, CHANGED_AT))).isFalse();
        assertThat(statuses.find(ownerId)).isEmpty();
    }

    @Test
    @DisplayName("存量链路不经过闸门：货主已停用时既有受理照样受理并执行")
    void 存量链路不判定() {
        long ownerId = applyOwnerStatus("DISABLED", 4, Instant.now());

        // 单仓写接口只有变更申请一枚，受理快照闭集是 PENDING（ck_site_archive_acceptance_snapshot）。
        SiteAcceptanceService.Ticket ticket = siteAcceptanceService.submit(SITE_ID, "API-011",
            "owner-blocking-" + nextId(), "/api/site/archives", "{\"ownerId\":" + ownerId + "}",
            "PENDING",
            acceptNo -> "{\"acceptNo\":\"" + acceptNo + "\"}");
        registerAcceptNo(ticket.acceptNo());

        assertThat(ticket.acceptNo()).isNotBlank();
        assertThat(siteAcceptanceService.find(SITE_ID, ticket.acceptNo()).acceptStatus())
            .as("停用货主不阻断存量单据的执行").isEqualTo("SUCCEEDED");
    }
}
