package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.domain.OwnerAction;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.domain.RuleViolation;
import com.huoling.wms.masterdata.owner.domain.OwnerArchive;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveQuery;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveRepository;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerNumberSource;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusChangedEvent;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主档案写侧应用服务：域为空不查询、版本冲突只有一条成功、信用代码重复翻译成契约码
 * （tasks 1.8 的验证口径）。
 *
 * <p>用内存仓储替身，验证「判定与落库顺序」；真正的行锁与触发器由数据库保证，
 * 打印出的 SQL 条件见 {@code OwnerArchiveRepositoryImpl}。
 */
class OwnerCommandServiceTest {

    private static final String OWNER_NO = "OW000001";
    private static final String CREDIT_CODE = "91350100MA2Y1234XY";

    private FakeOwnerRepository repository;
    private RecordingOutbox outbox;
    private OwnerCommandService service;

    @BeforeEach
    void setUp() {
        repository = new FakeOwnerRepository();
        outbox = new RecordingOutbox();
        AtomicInteger sequence = new AtomicInteger(1);
        OwnerNumberSource numbers = () -> "OW" + String.format("%06d", sequence.getAndIncrement());
        service = new OwnerCommandService(repository, numbers, outbox, event -> { });
    }

    @Test
    @DisplayName("空域直接拒绝且不查库，绝不回落成全部")
    void 空域拒绝且不查询() {
        repository.rows.put(1L, archive(1L, 0L));

        assertThatThrownBy(() -> service.edit(OWNER_NO, OwnerScope.of(Set.of()), 0L,
            fields("改个名字"), 88L))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
        assertThat(repository.findCalls.get()).isZero();
        assertThat(repository.updateCalls.get()).isZero();
    }

    @Test
    @DisplayName("域外目标按不存在处理，与「真不存在」同一响应")
    void 域外按不存在() {
        repository.rows.put(1L, archive(1L, 0L));

        assertThatThrownBy(() -> service.edit(OWNER_NO, OwnerScope.of(Set.of(9L)), 0L,
            fields("改个名字"), 88L))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);
        assertThatThrownBy(() -> service.edit("OW999999", OwnerScope.all(), 0L,
            fields("改个名字"), 88L))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);
    }

    @Test
    @DisplayName("版本落后按 CONFLICT_VERSION 拒绝，版本只前进一次")
    void 版本冲突() {
        repository.rows.put(1L, archive(1L, 0L));
        service.edit(OWNER_NO, OwnerScope.all(), 0L, fields("第一次改名"), 88L);

        // 版本前置比对在聚合里抛 Kernel 的规则异常，与落库行数 0 的翻译同码（CONFLICT_VERSION）。
        assertThatThrownBy(() -> service.edit(OWNER_NO, OwnerScope.all(), 0L,
            fields("第二次改名"), 88L))
            .isInstanceOf(RuleViolation.class)
            .extracting(e -> ((RuleViolation) e).code())
            .isEqualTo(OwnerErrorCodes.CONFLICT_VERSION);
        assertThat(repository.rows.get(1L).version()).isEqualTo(1L);
        assertThat(repository.rows.get(1L).fields().ownerName()).isEqualTo("第一次改名");
    }

    @Test
    @DisplayName("并发停用只有一条成功：后到者拿到可重试的版本冲突")
    void 并发停用只有一条成功() {
        repository.rows.put(1L, archive(1L, 0L));

        service.disable(OWNER_NO, OwnerScope.all(), 0L, "合同到期", 88L);

        assertThatThrownBy(() -> service.disable(OWNER_NO, OwnerScope.all(), 0L, "并发的第二笔", 99L))
            .isInstanceOf(RuleViolation.class)
            .extracting(e -> ((RuleViolation) e).code())
            .isEqualTo(OwnerErrorCodes.CONFLICT_VERSION);
        assertThat(repository.rows.get(1L).status()).isEqualTo(OwnerStatus.DISABLED);
        assertThat(repository.rows.get(1L).lastStatusChange().reason()).isEqualTo("合同到期");
        assertThat(repository.rows.get(1L).version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("落库时版本被他人推进（受影响行数为 0）同样按版本冲突处理")
    void 落库版本冲突() {
        repository.rows.put(1L, archive(1L, 0L));
        repository.forceUpdateFailure.set(true);

        assertThatThrownBy(() -> service.disable(OWNER_NO, OwnerScope.all(), 0L, "合同到期", 88L))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.CONFLICT_VERSION);
    }

    @Test
    @DisplayName("非全域账号建档按 FORBIDDEN_OWNER 拒绝")
    void 非全域不可建档() {
        assertThatThrownBy(() -> service.create(OwnerScope.of(Set.of(1L)), fields("华东仓储有限公司"), 88L))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
        assertThat(repository.rows).isEmpty();
    }

    @Test
    @DisplayName("建档：编号由序列给出，状态可用、版本 0")
    void 建档() {
        OwnerCommandService.OwnerResultView created =
            service.create(OwnerScope.all(), fields("华东仓储有限公司"), 88L);

        assertThat(created.ownerNo()).isEqualTo(OWNER_NO);
        assertThat(created.status()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(created.version()).isZero();
        assertThat(created.ownerId()).isNotBlank();
        assertThat(repository.rows).hasSize(1);
    }

    @Test
    @DisplayName("信用代码重复翻译成 RULE_DUPLICATE_CREDIT_CODE，不落第二条")
    void 信用代码重复() {
        repository.rows.put(1L, archive(1L, 0L));
        repository.failInsertWithCreditCodeClash.set(true);

        assertThatThrownBy(() -> service.create(OwnerScope.all(), fields("另一个主体"), 88L))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.RULE_DUPLICATE_CREDIT_CODE);
    }

    @Test
    @DisplayName("停用后启用：编号不变、版本递增、留痕为启用")
    void 停用后启用() {
        repository.rows.put(1L, archive(1L, 0L));
        service.disable(OWNER_NO, OwnerScope.all(), 0L, "合同到期", 88L);

        OwnerCommandService.OwnerResultView enabled =
            service.enable(OWNER_NO, OwnerScope.all(), 1L, "合同续签", 99L);

        assertThat(enabled.status()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(enabled.ownerNo()).isEqualTo(OWNER_NO);
        assertThat(enabled.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("建档、停用、启用各写一条状态变更事件；改档不写（tasks 1.13）")
    void 改档不发事件() {
        OwnerCommandService.OwnerResultView created =
            service.create(OwnerScope.all(), fields("华东仓储有限公司"), 88L);
        assertThat(outbox.events).extracting(OwnerStatusChangedEvent::eventId)
            .containsExactly(OWNER_NO + "#0");

        // 改档不改状态：版本前进一，但不产生下行事件。
        service.edit(created.ownerNo(), OwnerScope.all(), 0L, fields("改个名字"), 88L);
        assertThat(outbox.events).as("改档不改状态，不发事件").hasSize(1);

        service.disable(created.ownerNo(), OwnerScope.all(), 1L, "合同到期", 88L);
        service.enable(created.ownerNo(), OwnerScope.all(), 2L, "合同续签", 99L);

        assertThat(outbox.events).extracting(OwnerStatusChangedEvent::eventId)
            .as("事件标识 = 货主编号 + 版本号，停用与启用各产生一个新标识")
            .containsExactly(OWNER_NO + "#0", OWNER_NO + "#2", OWNER_NO + "#3");
        assertThat(outbox.events).extracting(OwnerStatusChangedEvent::action)
            .containsExactly(OwnerAction.CREATE, OwnerAction.DISABLE, OwnerAction.ENABLE);
        assertThat(outbox.events).extracting(OwnerStatusChangedEvent::status)
            .containsExactly(OwnerStatus.ACTIVE, OwnerStatus.DISABLED, OwnerStatus.ACTIVE);
    }

    /** 本地消息替身：只记录写了几条，事务与投递不在本用例范围。 */
    private static final class RecordingOutbox implements OwnerStatusOutbox {

        private final List<OwnerStatusChangedEvent> events = new java.util.ArrayList<>();

        @Override
        public void append(OwnerStatusChangedEvent event) {
            events.add(event);
        }
    }

    private static OwnerArchive archive(Long id, long version) {
        OwnerArchive created = OwnerArchive.create(id, OWNER_NO, fields("华东仓储有限公司"), 77L,
            java.time.Instant.parse("2026-11-25T08:30:00Z"));
        if (version == 0L) {
            return created;
        }
        throw new IllegalArgumentException("测试只用到版本 0 的初始档案");
    }

    private static OwnerFields fields(String ownerName) {
        return new OwnerFields(ownerName, "张三", "021-62345678", CREDIT_CODE);
    }

    /** 内存仓储替身：只模拟数据域与乐观锁，不做 SQL。 */
    private static final class FakeOwnerRepository implements OwnerArchiveRepository {

        private final Map<Long, OwnerArchive> rows = new LinkedHashMap<>();
        private final AtomicInteger findCalls = new AtomicInteger();
        private final AtomicInteger updateCalls = new AtomicInteger();
        private final AtomicBoolean forceUpdateFailure = new AtomicBoolean(false);
        private final AtomicBoolean failInsertWithCreditCodeClash = new AtomicBoolean(false);

        @Override
        public Optional<OwnerArchive> findByOwnerNo(String ownerNo, OwnerScope scope) {
            findCalls.incrementAndGet();
            if (scope == null || scope.empty()) {
                throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER,
                    "货主数据域为空，拒绝访问");
            }
            return rows.values().stream()
                .filter(row -> row.ownerNo().equals(ownerNo))
                .filter(row -> scope.covers(row.id()))
                .findFirst()
                .map(FakeOwnerRepository::copy);
        }

        /**
         * 复制一份再交给调用方：真仓储每次查库都是从行重建聚合，若替身返回同一个对象，
         * 聚合内的推进会被替身「提前」看见，乐观锁就无法模拟。
         */
        private static OwnerArchive copy(OwnerArchive source) {
            return OwnerArchive.restore(source.id(), source.ownerNo(), source.fields(),
                source.status(), source.lastStatusChange(), source.version(), source.createdAt(),
                source.createdBy(), source.updatedAt(), source.updatedBy());
        }

        @Override
        public List<OwnerArchive> page(OwnerArchiveQuery query) {
            // 写侧用例不查列表：列表语义由 OwnerArchiveApiIT 在真库上覆盖。
            return List.of();
        }

        @Override
        public void insert(OwnerArchive archive) {
            if (failInsertWithCreditCodeClash.get()) {
                throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"uk_wms_owner_credit_code\"");
            }
            rows.put(archive.id(), copy(archive));
        }

        @Override
        public boolean update(OwnerArchive archive, long expectedVersion) {
            updateCalls.incrementAndGet();
            OwnerArchive current = rows.get(archive.id());
            if (current == null || forceUpdateFailure.get() || current.version() != expectedVersion) {
                return false;
            }
            rows.put(archive.id(), copy(archive));
            return true;
        }
    }
}
