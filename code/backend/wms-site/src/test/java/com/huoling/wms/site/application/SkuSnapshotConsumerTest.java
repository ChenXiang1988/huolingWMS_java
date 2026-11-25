package com.huoling.wms.site.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.SiteChangeRequest;
import com.huoling.wms.site.domain.SiteChangeRequestRepository;
import com.huoling.wms.site.domain.SkuSnapshot;
import com.huoling.wms.site.domain.SkuSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下行消费（tasks 1.10、1.12）：按事件标识去重、落后版本丢弃并保留数据时间戳。
 *
 * <p>内存仓储替身复刻真实实现的「版本更大才覆盖」条件，验证消费侧的判定顺序与落库结果。
 */
class SkuSnapshotConsumerTest {

    private static final long SITE_ID = 1001L;
    private static final Instant DATA_TIMESTAMP = Instant.parse("2026-11-28T10:00:00Z");

    private FakeSnapshotRepository snapshots;
    private FakeChangeRequestRepository changeRequests;
    private SkuSnapshotConsumer consumer;

    @BeforeEach
    void setUp() {
        snapshots = new FakeSnapshotRepository();
        changeRequests = new FakeChangeRequestRepository();
        SiteProperties properties = new SiteProperties();
        properties.getSite().setId(SITE_ID);
        consumer = new SkuSnapshotConsumer(snapshots, changeRequests, properties,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    @DisplayName("生效摘要落到本仓缓存，版本与数据时间戳取自消息而不是消费时间")
    void 应用生效摘要() {
        boolean applied = consumer.consume("SKU-1#2", SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED,
            archivePayload("SKU-1", "ACTIVE", 2));

        assertThat(applied).isTrue();
        SkuSnapshot snapshot = snapshots.rows.get("SKU-1");
        assertThat(snapshot.version()).isEqualTo(2L);
        assertThat(snapshot.siteId()).isEqualTo(SITE_ID);
        assertThat(snapshot.dataTimestamp()).isEqualTo(DATA_TIMESTAMP);
        assertThat(snapshot.eventId()).isEqualTo("SKU-1#2");
    }

    @Test
    @DisplayName("版本不高于已应用版本时丢弃，缓存保持原样")
    void 落后版本丢弃() {
        consumer.consume("SKU-1#3", SkuSnapshotConsumer.TYPE_ARCHIVE_CHANGED,
            archivePayload("SKU-1", "ACTIVE", 3));

        boolean applied = consumer.consume("SKU-1#2", SkuSnapshotConsumer.TYPE_ARCHIVE_CHANGED,
            archivePayload("SKU-1", "ACTIVE", 2));

        assertThat(applied).isFalse();
        assertThat(snapshots.rows.get("SKU-1").version()).isEqualTo(3L);
        assertThat(snapshots.rows.get("SKU-1").dataTimestamp()).isEqualTo(DATA_TIMESTAMP);
    }

    @Test
    @DisplayName("未生效档案不进本仓缓存")
    void 非生效态丢弃() {
        boolean applied = consumer.consume("SKU-2#1", SkuSnapshotConsumer.TYPE_ARCHIVE_ACTIVATED,
            archivePayload("SKU-2", "DRAFT", 1));

        assertThat(applied).isFalse();
        assertThat(snapshots.rows).isEmpty();
    }

    @Test
    @DisplayName("变更申请审核结果落到本仓申请记录，驳回原因随事件下行")
    void 应用审核结果() {
        boolean applied = consumer.consume("SCR-1#REJECTED#1",
            SkuSnapshotConsumer.TYPE_CHANGE_REQUEST_REVIEWED,
            reviewPayload("SCR-1", "REJECTED", "规格描述不符"));

        assertThat(applied).isTrue();
        assertThat(changeRequests.applied).hasSize(1);
        assertThat(changeRequests.applied.get(0)).containsExactly("SCR-1", "REJECTED", "规格描述不符",
            "SCR-1#REJECTED#1");
    }

    @Test
    @DisplayName("非本仓的申请结果不落地")
    void 非本仓结果丢弃() {
        boolean applied = consumer.consume("SCR-9#APPROVED#1",
            SkuSnapshotConsumer.TYPE_CHANGE_REQUEST_REVIEWED,
            reviewPayload("SCR-9", "APPROVED", null).replace("\"siteId\":1001", "\"siteId\":2002"));

        assertThat(applied).isFalse();
        assertThat(changeRequests.applied).isEmpty();
    }

    private String archivePayload(String archiveId, String status, long version) {
        return """
            {"archiveId":"%s","ownerId":"9","ownerCode":"OWNER-1","name":"矿泉水","barcode":"6901234567890",
             "baseUnit":"瓶","storageCondition":"常温","category":"饮料","spec":"550ml","status":"%s",
             "version":%d,"dataTimestamp":"%s","changedFields":["status"]}
            """.formatted(archiveId, status, version, DATA_TIMESTAMP);
    }

    private String reviewPayload(String requestNo, String status, String reviewReason) {
        String reason = reviewReason == null ? "null" : "\"" + reviewReason + "\"";
        return """
            {"requestNo":"%s","archiveId":"SKU-1","status":"%s","reviewReason":%s,
             "archiveVersion":3,"siteId":1001,"dataTimestamp":"%s"}
            """.formatted(requestNo, status, reason, DATA_TIMESTAMP);
    }

    private static final class FakeSnapshotRepository implements SkuSnapshotRepository {

        private final Map<String, SkuSnapshot> rows = new HashMap<>();

        @Override
        public Optional<SkuSnapshot> find(Long siteId, String archiveNo) {
            return Optional.ofNullable(rows.get(archiveNo));
        }

        @Override
        public List<SkuSnapshot> page(SnapshotQuery query) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public void apply(SkuSnapshot snapshot) {
            SkuSnapshot cached = rows.get(snapshot.archiveNo());
            if (cached != null && snapshot.version() <= cached.version()) {
                return;
            }
            rows.put(snapshot.archiveNo(), snapshot);
        }
    }

    private static final class FakeChangeRequestRepository implements SiteChangeRequestRepository {

        private final List<List<String>> applied = new ArrayList<>();

        @Override
        public boolean hasPending(Long siteId, String archiveId) {
            return false;
        }

        @Override
        public void insert(SiteChangeRequest request) {
        }

        @Override
        public List<SiteChangeRequest> page(SiteChangeRequestQuery query) {
            return List.of();
        }

        @Override
        public List<SiteChangeRequest> findUploadable(int limit, Instant now) {
            return List.of();
        }

        @Override
        public void markUploaded(String requestNo, Instant now) {
        }

        @Override
        public void markUploadFailed(String requestNo, String error, Instant now) {
        }

        @Override
        public void applyReviewResult(String requestNo, String status, String reviewReason,
                                      Long archiveReviewedVersion, String eventId, Instant now) {
            applied.add(List.of(requestNo, status, String.valueOf(reviewReason), eventId));
        }

        @Override
        public Optional<SiteChangeRequest> findByRequestNo(Long siteId, String requestNo) {
            return Optional.empty();
        }
    }
}
