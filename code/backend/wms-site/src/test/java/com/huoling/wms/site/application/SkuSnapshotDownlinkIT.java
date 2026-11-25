package com.huoling.wms.site.application;

import com.huoling.wms.masterdata.domain.SkuRuleCodes;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import com.huoling.wms.site.domain.SkuSnapshot;
import com.huoling.wms.site.support.SiteDbTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下行消费（specs product-archive-review「生效同步与一致性」、契约 API-010）：
 * 按事件标识去重、版本落后丢弃、保留权威数据时间戳、只缓存生效与停用两态。
 *
 * <p>连真库：去重与版本不倒退的最终手段在 {@code uk_site_sku_snapshot_event} 与
 * {@code where excluded.version > site_sku_snapshot.version} 上，内存替身验不到。
 */
@SpringBootTest(classes = com.huoling.wms.site.SiteApplication.class,
    properties = {"wms.site.id=1001"})
class SkuSnapshotDownlinkIT extends SiteDbTestSupport {

    /** 权威数据的产生时间，刻意取过去时刻：消费时间必然晚于它。 */
    private static final Instant DATA_TIMESTAMP = Instant.parse("2026-01-05T08:00:00Z");

    @Test
    @DisplayName("生效摘要落到本仓缓存，版本与数据时间戳取自消息而不是消费时间")
    void 应用生效摘要() {
        String archiveNo = "SKU" + nextId();
        registerArchiveNo(archiveNo);

        boolean applied = snapshotConsumer.consume(archiveNo + "#2",
            SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED,
            archivePayload(archiveNo, "ACTIVE", 2, DATA_TIMESTAMP));

        assertThat(applied).isTrue();
        SkuSnapshot snapshot = snapshotRepository.find(SITE_ID, archiveNo).orElseThrow();
        assertThat(snapshot.version()).isEqualTo(2L);
        assertThat(snapshot.siteId()).isEqualTo(SITE_ID);
        assertThat(snapshot.dataTimestamp()).isEqualTo(DATA_TIMESTAMP);
        assertThat(snapshot.appliedAt()).as("落库时间是消费时间，晚于权威数据时间")
            .isAfter(DATA_TIMESTAMP);
    }

    @Test
    @DisplayName("同一事件标识重复投递只应用一次，缓存版本与事件标识都不变")
    void 事件标识去重() {
        String archiveNo = "SKU" + nextId();
        registerArchiveNo(archiveNo);
        String payload = archivePayload(archiveNo, "ACTIVE", 2, DATA_TIMESTAMP);

        assertThat(snapshotConsumer.consume(archiveNo + "#2",
            SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED, payload)).isTrue();
        boolean second = snapshotConsumer.consume(archiveNo + "#2",
            SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED, payload);

        assertThat(second).as("重复投递不得重复应用").isFalse();
        assertThat(jdbc.queryForObject(
            "select count(*) from site_sku_snapshot where archive_no = ?", Integer.class, archiveNo))
            .isEqualTo(1);
        SkuSnapshot snapshot = snapshotRepository.find(SITE_ID, archiveNo).orElseThrow();
        assertThat(snapshot.eventId()).isEqualTo(archiveNo + "#2");
        assertThat(snapshot.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("事件标识在数据库层唯一：同一事件标识写到另一个档案会被拒绝")
    void 事件标识唯一索引兜底() {
        String first = "SKU" + nextId();
        String second = "SKU" + nextId();
        registerArchiveNo(first);
        registerArchiveNo(second);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String sharedEventId = "EVENT-" + nextId();

        jdbc.update("""
            insert into site_sku_snapshot (id, warehouse_id, owner_id, archive_id, archive_no,
                owner_code, name, barcode, base_unit, storage_condition, category, specification,
                status, version, event_id, data_timestamp, applied_at)
            values (?, ?, ?, ?, ?, 'T-S-9', '档案一', '6900', '瓶', '常温', null, null,
                'ACTIVE', 1, ?, ?, ?)
            """, nextId(), SITE_ID, nextId(), nextId(), first, sharedEventId, now, now);

        assertThatThrownBy(() -> jdbc.update("""
            insert into site_sku_snapshot (id, warehouse_id, owner_id, archive_id, archive_no,
                owner_code, name, barcode, base_unit, storage_condition, category, specification,
                status, version, event_id, data_timestamp, applied_at)
            values (?, ?, ?, ?, ?, 'T-S-9', '档案二', '6901', '瓶', '常温', null, null,
                'ACTIVE', 1, ?, ?, ?)
            """, nextId(), SITE_ID, nextId(), nextId(), second, sharedEventId, now, now))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("版本不高于已应用版本时丢弃，缓存版本不倒退、数据时间戳不回退")
    void 落后版本丢弃() {
        String archiveNo = "SKU" + nextId();
        registerArchiveNo(archiveNo);
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");
        snapshotConsumer.consume(archiveNo + "#3", SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED,
            archivePayload(archiveNo, "ACTIVE", 3, newer));

        boolean applied = snapshotConsumer.consume(archiveNo + "#2",
            SkuSnapshotConsumer.TYPE_ARCHIVE_CHANGED,
            archivePayload(archiveNo, "ACTIVE", 2, DATA_TIMESTAMP));

        assertThat(applied).isFalse();
        SkuSnapshot snapshot = snapshotRepository.find(SITE_ID, archiveNo).orElseThrow();
        assertThat(snapshot.version()).isEqualTo(3L);
        assertThat(snapshot.dataTimestamp()).isEqualTo(newer);
    }

    @Test
    @DisplayName("未生效档案不进本仓缓存，停用档案进缓存")
    void 只缓存生效与停用两态() {
        String draft = "SKU" + nextId();
        String pending = "SKU" + nextId();
        String disabled = "SKU" + nextId();
        registerArchiveNo(draft);
        registerArchiveNo(pending);
        registerArchiveNo(disabled);

        assertThat(snapshotConsumer.consume(draft + "#1",
            SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED,
            archivePayload(draft, "DRAFT", 1, DATA_TIMESTAMP))).isFalse();
        assertThat(snapshotConsumer.consume(pending + "#1",
            SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED,
            archivePayload(pending, "PENDING", 1, DATA_TIMESTAMP))).isFalse();
        assertThat(snapshotRepository.find(SITE_ID, draft)).isEmpty();
        assertThat(snapshotRepository.find(SITE_ID, pending)).isEmpty();

        assertThat(snapshotConsumer.consume(disabled + "#2",
            SkuSnapshotConsumer.TYPE_ARCHIVE_CHANGED,
            archivePayload(disabled, "DISABLED", 2, DATA_TIMESTAMP))).isTrue();
        assertThat(snapshotRepository.find(SITE_ID, disabled).orElseThrow().status())
            .isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("摘要缺版本号或版本非正按参数错误拒绝，不写入缓存")
    void 摘要缺版本号拒绝() {
        String archiveNo = "SKU" + nextId();
        registerArchiveNo(archiveNo);

        assertThatThrownBy(() -> snapshotConsumer.consume(archiveNo + "#0",
            SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED,
            archivePayload(archiveNo, "ACTIVE", 0, DATA_TIMESTAMP)))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.PARAM_INVALID);

        assertThat(snapshotRepository.find(SITE_ID, archiveNo)).isEmpty();
    }
}
