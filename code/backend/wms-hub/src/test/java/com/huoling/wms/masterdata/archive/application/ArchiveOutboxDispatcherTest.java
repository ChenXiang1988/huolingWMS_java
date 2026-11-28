package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.common.owner.OwnerStatusDeliveryObserver;
import com.huoling.wms.masterdata.archive.domain.ArchiveEventTransport;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地消息投递器（tasks 1.12、1.13）：抢占后投递、失败记重试、被抢走的记录不重复投递，
 * 以及货主状态事件的重试耗尽转发死信。
 */
class ArchiveOutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    private static final int THRESHOLD = 3;
    private static final String OWNER_EVENT_TYPE = "owner.status.changed";

    private FakeOutboxRepository repository;
    private RecordingTransport transport;
    private RecordingObserver observer;
    private ArchiveOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = new FakeOutboxRepository();
        transport = new RecordingTransport(false);
        observer = new RecordingObserver();
        dispatcher = new ArchiveOutboxDispatcher(repository, transport, 50, THRESHOLD, observer);
    }

    @Test
    @DisplayName("投递成功的消息标记已发送")
    void 投递成功() {
        repository.rows.add(message(1L, "SKU-1#2", ArchiveOutboxMessage.TYPE_ARCHIVE_ACTIVATED));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(transport.published).containsExactly("SKU-1#2");
        assertThat(repository.sent).containsExactly(1L);
        assertThat(repository.failed).isEmpty();
        assertThat(transport.deadLettered).as("成功投递不产生死信").isEmpty();
        assertThat(observer.delivered)
            .as("商品档案下行不带货主状态的延迟指标，只对 OWNER 事件回调").isEmpty();
    }

    @Test
    @DisplayName("货主状态事件投递成功：按载荷 changedAt 上报延迟（不按投递时间）")
    void 货主状态事件上报下行延迟() {
        repository.rows.add(ownerMessage(5L, "OW000001#3",
            "{\"eventId\":\"OW000001#3\",\"changedAt\":\"2026-11-28T09:59:30Z\",\"version\":3}"));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(observer.delivered).hasSize(1);
        assertThat(observer.delivered.get(0).eventId()).isEqualTo("OW000001#3");
        assertThat(observer.delivered.get(0).changedAt())
            .as("changedAt 取载荷里的权威产生时间，落库时间只是回落值")
            .isEqualTo(Instant.parse("2026-11-28T09:59:30Z"));
    }

    @Test
    @DisplayName("载荷缺 changedAt 时回落到落库时间，不抛异常、不误报零延迟")
    void 载荷缺changedAt回落到落库时间() {
        repository.rows.add(ownerMessage(6L, "OW000002#4", "{}"));

        dispatcher.dispatchOnce();

        assertThat(observer.delivered).hasSize(1);
        assertThat(observer.delivered.get(0).changedAt())
            .as("回落值仍是同一事务写入的行时间，与 changedAt 只差微秒级")
            .isEqualTo(NOW);
    }

    @Test
    @DisplayName("投递失败的消息记失败并按退避重试，不阻塞后续消息")
    void 投递失败() {
        transport.failing = true;
        repository.rows.add(message(1L, "SKU-1#2", ArchiveOutboxMessage.TYPE_ARCHIVE_ACTIVATED));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isZero();
        assertThat(repository.sent).isEmpty();
        assertThat(repository.failed).containsExactly(1L);
        assertThat(transport.deadLettered)
            .as("商品档案下行不转死信：本变更只给货主状态事件定义死信队列").isEmpty();
    }

    @Test
    @DisplayName("抢占失败的消息跳过，多副本不重复投递")
    void 抢占失败跳过() {
        repository.claimable = false;
        repository.rows.add(message(1L, "SKU-1#2", ArchiveOutboxMessage.TYPE_ARCHIVE_ACTIVATED));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isZero();
        assertThat(transport.published).isEmpty();
    }

    @Test
    @DisplayName("货主状态事件重试到达阈值即转发死信并告警，未到阈值不转发")
    void 货主状态事件重试耗尽转死信() {
        transport.failing = true;
        repository.rows.add(message(9L, "OW000001#2", OWNER_EVENT_TYPE));

        repository.retryCountAfterFailure = THRESHOLD - 1;
        dispatcher.dispatchOnce();
        assertThat(transport.deadLettered).as("未到阈值不转死信").isEmpty();

        repository.retryCountAfterFailure = THRESHOLD;
        dispatcher.dispatchOnce();
        assertThat(transport.deadLettered).containsExactly("OW000001#2");
        assertThat(observer.deadLettered)
            .as("死信转发成功后上报深度，人工重放时按 eventId 定位").containsExactly("OW000001#2");

        repository.retryCountAfterFailure = THRESHOLD + 1;
        dispatcher.dispatchOnce();
        assertThat(transport.deadLettered)
            .as("一条消息只转发一次，阈值之后继续退避重投").containsExactly("OW000001#2");
        assertThat(observer.deadLettered)
            .as("深度只随真实转发增长，重投不重复计数").containsExactly("OW000001#2");
    }

    @Test
    @DisplayName("观测回调抛异常不影响投递结果：观测是旁路")
    void 观测失败不改投递结果() {
        observer.failing = true;
        repository.rows.add(ownerMessage(7L, "OW000003#5",
            "{\"changedAt\":\"2026-11-28T09:00:00Z\"}"));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).as("观测抛异常被吞掉，投递仍算成功").isEqualTo(1);
        assertThat(repository.sent).containsExactly(7L);
        assertThat(repository.failed).isEmpty();
    }

    private ArchiveOutboxMessage message(Long id, String eventId, String eventType) {
        return new ArchiveOutboxMessage(id, eventId, eventType,
            "SKU-1", 2L, 9L, "{}", NOW);
    }

    /** 货主状态事件：{@code event_type} 带 OWNER 前缀，载荷形态与 {@code OwnerStatusChangedEvent} 一致。 */
    private ArchiveOutboxMessage ownerMessage(Long id, String eventId, String payloadJson) {
        return new ArchiveOutboxMessage(id, eventId, OWNER_EVENT_TYPE,
            "OW000001", 3L, 9L, payloadJson, NOW);
    }

    /** 观测回调的替身：记录收到的内容，可模拟观测自身失败。 */
    private static final class RecordingObserver implements OwnerStatusDeliveryObserver {

        private final List<Delivered> delivered = new ArrayList<>();
        private final List<String> deadLettered = new ArrayList<>();
        private boolean failing;

        @Override
        public void delivered(String eventId, Instant changedAt) {
            if (failing) {
                throw new IllegalStateException("meter registry down");
            }
            delivered.add(new Delivered(eventId, changedAt));
        }

        @Override
        public void deadLettered(String eventId) {
            deadLettered.add(eventId);
        }

        private record Delivered(String eventId, Instant changedAt) {
        }
    }

    private static final class RecordingTransport implements ArchiveEventTransport {

        private final List<String> published = new ArrayList<>();
        private final List<String> deadLettered = new ArrayList<>();
        private boolean failing;

        private RecordingTransport(boolean failing) {
            this.failing = failing;
        }

        @Override
        public void publish(ArchiveOutboxMessage message) {
            if (failing) {
                throw new IllegalStateException("broker down");
            }
            published.add(message.eventId());
        }

        @Override
        public void deadLetter(ArchiveOutboxMessage message, String reason) {
            deadLettered.add(message.eventId());
        }
    }

    private static final class FakeOutboxRepository implements ArchiveOutboxRepository {

        private final List<ArchiveOutboxMessage> rows = new ArrayList<>();
        private final Set<Long> sent = new HashSet<>();
        private final Set<Long> failed = new HashSet<>();
        private boolean claimable = true;
        private int retryCountAfterFailure = 1;

        @Override
        public void insert(ArchiveOutboxMessage message) {
            rows.add(message);
        }

        @Override
        public List<ArchiveOutboxMessage> findDispatchable(int limit, Instant now) {
            return List.copyOf(rows);
        }

        @Override
        public boolean claim(Long id) {
            return claimable;
        }

        @Override
        public void markSent(Long id, Instant now) {
            sent.add(id);
        }

        @Override
        public int markFailed(Long id, String error, Instant now) {
            failed.add(id);
            return retryCountAfterFailure;
        }
    }
}
