package com.huoling.wms.masterdata.archive.application;

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
 * 本地消息投递器（tasks 1.12）：抢占后投递、失败记重试、被抢走的记录不重复投递。
 */
class ArchiveOutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

    private FakeOutboxRepository repository;
    private RecordingTransport transport;
    private ArchiveOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = new FakeOutboxRepository();
        transport = new RecordingTransport(false);
        dispatcher = new ArchiveOutboxDispatcher(repository, transport, 50);
    }

    @Test
    @DisplayName("投递成功的消息标记已发送")
    void 投递成功() {
        repository.rows.add(message(1L, "SKU-1#2"));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(transport.published).containsExactly("SKU-1#2");
        assertThat(repository.sent).containsExactly(1L);
        assertThat(repository.failed).isEmpty();
    }

    @Test
    @DisplayName("投递失败的消息记失败并按退避重试，不阻塞后续消息")
    void 投递失败() {
        transport.failing = true;
        repository.rows.add(message(1L, "SKU-1#2"));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isZero();
        assertThat(repository.sent).isEmpty();
        assertThat(repository.failed).containsExactly(1L);
    }

    @Test
    @DisplayName("抢占失败的消息跳过，多副本不重复投递")
    void 抢占失败跳过() {
        repository.claimable = false;
        repository.rows.add(message(1L, "SKU-1#2"));

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isZero();
        assertThat(transport.published).isEmpty();
    }

    private ArchiveOutboxMessage message(Long id, String eventId) {
        return new ArchiveOutboxMessage(id, eventId, ArchiveOutboxMessage.TYPE_ARCHIVE_ACTIVATED,
            "SKU-1", 2L, 9L, "{}", NOW);
    }

    private static final class RecordingTransport implements ArchiveEventTransport {

        private final List<String> published = new ArrayList<>();
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
    }

    private static final class FakeOutboxRepository implements ArchiveOutboxRepository {

        private final List<ArchiveOutboxMessage> rows = new ArrayList<>();
        private final Set<Long> sent = new HashSet<>();
        private final Set<Long> failed = new HashSet<>();
        private boolean claimable = true;

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
        public void markFailed(Long id, String error, Instant now) {
            failed.add(id);
        }
    }
}
