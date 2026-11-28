package com.huoling.wms.masterdata.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxRepository;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveQuery;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.archive.domain.SkuReviewRecord;
import com.huoling.wms.masterdata.archive.domain.SkuReviewRepository;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuArchiveDedupRule;
import com.huoling.wms.masterdata.domain.SkuArchiveFields;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审核事务边界：通过时状态、版本、留痕与下行消息一起写；失败时一条都不留（tasks 1.3 的验证口径）。
 *
 * <p>用内存仓储替身验证「写序」，事务回滚本身由数据库保证，这里验证失败分支不会产生半成品记录。
 */
class SkuReviewServiceTest {

    private FakeArchiveRepository archives;
    private FakeReviewRepository reviews;
    private FakeOutboxRepository outbox;
    private SkuReviewService service;

    @BeforeEach
    void setUp() {
        archives = new FakeArchiveRepository();
        reviews = new FakeReviewRepository();
        outbox = new FakeOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new SkuReviewService(archives, reviews, outbox, objectMapper, null);
    }

    @Test
    @DisplayName("审核通过：状态启用、版本递增、留痕一条、下行消息一条")
    void 审核通过同事务写四件事() {
        archives.put(pendingArchive());

        SkuReviewService.ReviewView view = service.review("SKU-1", SkuArchiveAction.APPROVE, null, 77L);

        assertThat(view.status()).isEqualTo(SkuArchiveStatus.ACTIVE);
        assertThat(view.version()).isEqualTo(2L);
        assertThat(reviews.records).hasSize(1);
        assertThat(reviews.records.get(0).action()).isEqualTo(SkuArchiveAction.APPROVE);
        assertThat(reviews.records.get(0).fromStatus()).isEqualTo(SkuArchiveStatus.PENDING);
        assertThat(reviews.records.get(0).toStatus()).isEqualTo(SkuArchiveStatus.ACTIVE);
        assertThat(outbox.messages).hasSize(1);
        assertThat(outbox.messages.get(0).eventId()).isEqualTo("SKU-1#2");
        assertThat(outbox.messages.get(0).eventType())
            .isEqualTo(ArchiveOutboxMessage.TYPE_ARCHIVE_ACTIVATED);
        assertThat(outbox.messages.get(0).payloadJson()).contains("\"version\":2");
    }

    @Test
    @DisplayName("版本冲突时不留留痕与消息，档案保持待审核")
    void 版本冲突无半成品() {
        archives.put(pendingArchive());
        archives.failUpdate = true;

        assertThatThrownBy(() -> service.review("SKU-1", SkuArchiveAction.APPROVE, null, 77L))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.CONFLICT_VERSION);

        assertThat(reviews.records).isEmpty();
        assertThat(outbox.messages).isEmpty();
    }

    @Test
    @DisplayName("驳回保留原因但不产生下行消息")
    void 驳回不下行() {
        archives.put(pendingArchive());

        SkuReviewService.ReviewView view = service.review("SKU-1", SkuArchiveAction.REJECT, "规格不符", 77L);

        assertThat(view.status()).isEqualTo(SkuArchiveStatus.REJECTED);
        assertThat(reviews.records).hasSize(1);
        assertThat(reviews.records.get(0).reason()).isEqualTo("规格不符");
        assertThat(outbox.messages).isEmpty();
    }

    @Test
    @DisplayName("驳回未填原因按参数错误拒绝，档案保持待审核")
    void 驳回必须给原因() {
        archives.put(pendingArchive());

        assertThatThrownBy(() -> service.review("SKU-1", SkuArchiveAction.REJECT, "  ", 77L))
            .isInstanceOf(RuntimeException.class);

        assertThat(reviews.records).isEmpty();
        assertThat(archives.archive.status()).isEqualTo(SkuArchiveStatus.PENDING);
    }

    @Test
    @DisplayName("档案不存在返回 NOT_FOUND_ARCHIVE")
    void 档案不存在() {
        assertThatThrownBy(() -> service.review("SKU-NONE", SkuArchiveAction.APPROVE, null, 77L))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.NOT_FOUND_ARCHIVE);
    }

    @Test
    @DisplayName("审核动作只接受通过与驳回")
    void 动作闭集() {
        archives.put(pendingArchive());

        assertThatThrownBy(() -> service.review("SKU-1", SkuArchiveAction.DISABLE, null, 77L))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    private static SkuArchive pendingArchive() {
        Instant now = Instant.parse("2026-11-28T10:00:00Z");
        SkuArchiveFields fields = new SkuArchiveFields("矿泉水", "OWNER-SKU-1", "6901234567890", "瓶",
            "常温", "饮料", "550ml", "自有", "EAN13", null, null, new BigDecimal("0.55"),
            new BigDecimal("0.60"), new BigDecimal("0.0006"), null, null, null, false, false, false,
            false, false, 365, null, null);
        SkuArchive archive = SkuArchive.createDraft(1L, 9L, "SKU-1", fields, now);
        archive.submit(null, now);
        return archive;
    }

    /** 内存仓储替身：只实现本测试用到的读取与更新。 */
    private static final class FakeArchiveRepository implements SkuArchiveRepository {

        private SkuArchive archive;
        private boolean failUpdate;

        void put(SkuArchive value) {
            this.archive = value;
        }

        @Override
        public Optional<SkuArchive> findByArchiveNo(String archiveNo) {
            return archive != null && archive.archiveNo().equals(archiveNo)
                ? Optional.of(archive) : Optional.empty();
        }

        @Override
        public Optional<SkuArchive> findByArchiveNoAndOwner(String archiveNo, Long ownerId) {
            return findByArchiveNo(archiveNo);
        }

        @Override
        public List<SkuArchive> findByArchiveNos(Collection<String> archiveNos) {
            return findByArchiveNo(archiveNos.iterator().next()).map(List::of).orElse(List.of());
        }

        @Override
        public List<SkuArchiveDedupRule.SkuIdentity> findIdentities(Long ownerId) {
            return List.of();
        }

        @Override
        public void insert(SkuArchive value, long operatorId) {
            this.archive = value;
        }

        @Override
        public boolean update(SkuArchive value, long expectedVersion, long operatorId) {
            if (failUpdate) {
                return false;
            }
            this.archive = value;
            return true;
        }

        @Override
        public List<SkuArchive> page(SkuArchiveQuery query) {
            return archive == null ? List.of() : List.of(archive);
        }
    }

    private static final class FakeReviewRepository implements SkuReviewRepository {

        private final List<SkuReviewRecord> records = new ArrayList<>();

        @Override
        public void insert(SkuReviewRecord record) {
            records.add(record);
        }
    }

    private static final class FakeOutboxRepository implements ArchiveOutboxRepository {

        private final List<ArchiveOutboxMessage> messages = new ArrayList<>();

        @Override
        public void insert(ArchiveOutboxMessage message) {
            messages.add(message);
        }

        @Override
        public List<ArchiveOutboxMessage> findDispatchable(int limit, Instant now) {
            return List.copyOf(messages);
        }

        @Override
        public boolean claim(Long id) {
            return true;
        }

        @Override
        public void markSent(Long id, Instant now) {
        }

        @Override
        public int markFailed(Long id, String error, Instant now) {
            return 1;
        }
    }
}
