package com.huoling.wms.masterdata.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxRepository;
import com.huoling.wms.masterdata.archive.domain.ChangeRequest;
import com.huoling.wms.masterdata.archive.domain.ChangeRequestQuery;
import com.huoling.wms.masterdata.archive.domain.ChangeRequestRepository;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveQuery;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.domain.SkuArchiveDedupRule;
import com.huoling.wms.masterdata.domain.SkuArchiveFields;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import com.huoling.wms.masterdata.domain.SkuChangeRequestStatus;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import com.huoling.wms.masterdata.identity.domain.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 变更申请：同一档案仅一条待审核、越仓拒绝、审核通过写回档案并下行、驳回保留原因。
 */
class ChangeRequestServiceTest {

    private static final long OWNER_ID = 9L;
    private static final long WAREHOUSE_ID = 1001L;

    private FakeArchiveRepository archives;
    private FakeChangeRequestRepository requests;
    private FakeOutboxRepository outbox;
    private ChangeRequestService service;
    private ArchiveAccessGuard.SiteScope scope;

    @BeforeEach
    void setUp() {
        archives = new FakeArchiveRepository();
        requests = new FakeChangeRequestRepository();
        outbox = new FakeOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ChangeRequestService(requests, archives, outbox, new ArchiveNumbers(), objectMapper);
        scope = new ArchiveAccessGuard.SiteScope(WAREHOUSE_ID,
            new DataScope(false, Set.of(OWNER_ID), false, Set.of(WAREHOUSE_ID)));
    }

    @Test
    @DisplayName("单仓发起申请：状态待审核，档案内容与版本不变")
    void 发起申请() {
        archives.put(activeArchive());

        ChangeRequestService.RequestCreatedView view = service.create("SKU-1", scope, "NAME",
            "矿泉水 550ml", "货主改了规格", 88L);

        assertThat(view.status()).isEqualTo(SkuChangeRequestStatus.PENDING);
        assertThat(view.requestNo()).startsWith("CRQ");
        assertThat(requests.rows).hasSize(1);
        assertThat(requests.rows.get(0).warehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(archives.archive.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("同一档案已有待审核申请，拒绝再发起")
    void 仅一条待审核() {
        archives.put(activeArchive());
        service.create("SKU-1", scope, "NAME", "新名称", "原因", 88L);

        assertThatThrownBy(() -> service.create("SKU-1", scope, "BRAND", "新品牌", "原因", 88L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("档案归属不在本仓可见范围，按越权拒绝")
    void 越仓拒绝() {
        archives.put(activeArchive());
        ArchiveAccessGuard.SiteScope otherScope = new ArchiveAccessGuard.SiteScope(WAREHOUSE_ID,
            new DataScope(false, Set.of(777L), false, Set.of(WAREHOUSE_ID)));

        assertThatThrownBy(() -> service.create("SKU-1", otherScope, "NAME", "新名称", "原因", 88L))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("草稿态档案不接受变更申请")
    void 非启用态拒绝() {
        archives.put(SkuArchive.createDraft(1L, OWNER_ID, "SKU-1", fields(),
            Instant.parse("2026-11-28T10:00:00Z")));

        assertThatThrownBy(() -> service.create("SKU-1", scope, "NAME", "新名称", "原因", 88L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("变更字段闭集外的取值按参数错误拒绝")
    void 字段闭集() {
        archives.put(activeArchive());

        assertThatThrownBy(() -> service.create("SKU-1", scope, "ownerId", "9", "原因", 88L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("审核通过：写回档案、递增版本、记录新版本号并产生下行消息")
    void 审核通过写回() {
        archives.put(activeArchive());
        ChangeRequestService.RequestCreatedView created =
            service.create("SKU-1", scope, "NAME", "矿泉水 600ml", "改规格", 88L);

        ChangeRequestService.RequestReviewedView view =
            service.review(created.requestNo(), true, null, 99L);

        assertThat(view.status()).isEqualTo(SkuChangeRequestStatus.APPROVED);
        assertThat(view.archiveVersion()).isEqualTo(3L);
        assertThat(archives.archive.fields().name()).isEqualTo("矿泉水 600ml");
        // 审核通过写两条消息：档案变更（供缓存同步）加审核结果（供申请跟踪）。
        List<ArchiveOutboxMessage> changed = outbox.byType(ArchiveOutboxMessage.TYPE_ARCHIVE_CHANGED);
        assertThat(changed).hasSize(1);
        assertThat(changed.get(0).eventId()).isEqualTo("SKU-1#3");
        assertThat(changed.get(0).payloadJson()).contains("NAME");
        assertThat(outbox.byType(ArchiveOutboxMessage.TYPE_CHANGE_REQUEST_REVIEWED)).hasSize(1);
    }

    @Test
    @DisplayName("审核驳回：档案不变，保留驳回原因，不产生下行消息")
    void 审核驳回() {
        archives.put(activeArchive());
        ChangeRequestService.RequestCreatedView created =
            service.create("SKU-1", scope, "NAME", "矿泉水 600ml", "改规格", 88L);

        ChangeRequestService.RequestReviewedView view =
            service.review(created.requestNo(), false, "规格描述不符", 99L);

        assertThat(view.status()).isEqualTo(SkuChangeRequestStatus.REJECTED);
        assertThat(view.archiveVersion()).isNull();
        assertThat(archives.archive.fields().name()).isEqualTo("矿泉水");
        assertThat(archives.archive.version()).isEqualTo(2L);
        // 驳回不产生档案变更消息，但仍要通知单仓申请结果（design「事件与回调」）。
        assertThat(outbox.byType(ArchiveOutboxMessage.TYPE_ARCHIVE_CHANGED)).isEmpty();
        assertThat(outbox.byType(ArchiveOutboxMessage.TYPE_CHANGE_REQUEST_REVIEWED)).hasSize(1);
        assertThat(requests.rows.get(0).reviewReason()).isEqualTo("规格描述不符");
    }

    @Test
    @DisplayName("已审核过的申请不能再审")
    void 不可重复审核() {
        archives.put(activeArchive());
        ChangeRequestService.RequestCreatedView created =
            service.create("SKU-1", scope, "NAME", "矿泉水 600ml", "改规格", 88L);
        service.review(created.requestNo(), true, null, 99L);

        assertThatThrownBy(() -> service.review(created.requestNo(), true, null, 99L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("申请不存在返回 NOT_FOUND_ARCHIVE")
    void 申请不存在() {
        assertThatThrownBy(() -> service.review("CRQ-NONE", true, null, 99L))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.NOT_FOUND_ARCHIVE);
    }

    private static SkuArchiveFields fields() {
        return new SkuArchiveFields("矿泉水", "OWNER-SKU-1", "6901234567890", "瓶", "常温",
            "饮料", "550ml", "自有", "EAN13", null, null, new BigDecimal("0.55"),
            new BigDecimal("0.60"), new BigDecimal("0.0006"), null, null, null, false, false, false,
            false, false, 365, null, null);
    }

    /** 已启用的档案：版本 2，来源是草稿提交后审核通过。 */
    private static SkuArchive activeArchive() {
        Instant now = Instant.parse("2026-11-28T10:00:00Z");
        SkuArchive archive = SkuArchive.createDraft(1L, OWNER_ID, "SKU-1", fields(), now);
        archive.submit(null, now);
        archive.approve(archive.version(), now);
        return archive;
    }

    private static final class FakeArchiveRepository implements SkuArchiveRepository {

        private SkuArchive archive;

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
            return archive == null ? List.of() : List.of(archive);
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
            this.archive = value;
            return true;
        }

        @Override
        public List<SkuArchive> page(SkuArchiveQuery query) {
            return archive == null ? List.of() : List.of(archive);
        }
    }

    private static final class FakeChangeRequestRepository implements ChangeRequestRepository {

        private final List<ChangeRequest> rows = new ArrayList<>();

        @Override
        public Optional<ChangeRequest> findByRequestNo(String requestNo) {
            return rows.stream().filter(row -> row.requestNo().equals(requestNo)).findFirst();
        }

        @Override
        public Optional<ChangeRequest> findByRequestNoAndWarehouse(String requestNo, Long warehouseId) {
            return findByRequestNo(requestNo)
                .filter(row -> row.warehouseId().equals(warehouseId));
        }

        @Override
        public boolean hasPending(Long archiveId) {
            return rows.stream().anyMatch(row -> row.archiveId().equals(archiveId)
                && row.status() == SkuChangeRequestStatus.PENDING);
        }

        @Override
        public void insert(ChangeRequest request, long operatorId) {
            rows.add(request);
        }

        @Override
        public boolean update(ChangeRequest request, long expectedVersion, long operatorId) {
            rows.replaceAll(row -> row.requestNo().equals(request.requestNo()) ? request : row);
            return true;
        }

        @Override
        public List<ChangeRequest> page(ChangeRequestQuery query) {
            return List.copyOf(rows);
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
        public void markFailed(Long id, String error, Instant now) {
        }

        List<ArchiveOutboxMessage> byType(String eventType) {
            return messages.stream().filter(message -> eventType.equals(message.eventType())).toList();
        }
    }
}
