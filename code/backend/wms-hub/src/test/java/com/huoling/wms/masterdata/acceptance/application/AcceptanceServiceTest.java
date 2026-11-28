package com.huoling.wms.masterdata.acceptance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.huoling.wms.masterdata.acceptance.domain.Acceptance;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceErrorCodes;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceException;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceQuery;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceRepository;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceStatus;
import com.huoling.wms.masterdata.acceptance.domain.IdempotencyRecord;
import com.huoling.wms.masterdata.acceptance.domain.IdempotencyRepository;
import com.huoling.wms.masterdata.acceptance.domain.IdempotencyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受理闭环：写接口只回受理号，受理查询覆盖处理中、成功与失败三态。
 *
 * <p>用内存仓储替身加同步执行器，验证五件事：首次受理落一条记录、重复提交回放同一受理号、
 * 同键异体拒绝、业务失败留下带错误码的受理记录，以及跨业务的接口号隔离与目标货主落库
 * （本次变更 tasks 1.7 的验证口径）。事务本身由数据库保证，这里验证写序与判定。
 */
class AcceptanceServiceTest {

    private static final String API = "API-001";
    private static final String OWNER_API = "API-024";
    private static final String SCOPE = "9";
    private static final String NOT_FOUND = "NOT_FOUND_ARCHIVE";

    private FakeAcceptanceRepository acceptances;
    private FakeIdempotencyRepository idempotencyRecords;
    private AcceptanceService service;

    @BeforeEach
    void setUp() {
        acceptances = new FakeAcceptanceRepository();
        idempotencyRecords = new FakeIdempotencyRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AcceptanceService(acceptances,
            new IdempotencyService(idempotencyRecords), new AcceptanceNumbers(), objectMapper,
            new TransactionTemplate(new NoopTransactionManager()), new DirectExecutor());
    }

    @Test
    @DisplayName("首次受理：返回受理号与状态快照，执行成功后受理记录为成功并带结果摘要")
    void 首次受理并成功() {
        AcceptanceService.Ticket ticket = service.submit(command("k-1"), () -> new Created("SKU-1"));

        assertThat(ticket.acceptNo()).startsWith("ARC");
        assertThat(ticket.status()).isEqualTo("DRAFT");
        AcceptanceService.View view = service.find(query(API), ticket.acceptNo());
        assertThat(view.acceptStatus()).isEqualTo(AcceptanceStatus.SUCCEEDED.name());
        assertThat(view.result().get("archiveId").asText()).isEqualTo("SKU-1");
        assertThat(view.dataTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("重复提交只产生一条受理记录，回放首次受理号且不重复执行")
    void 重复提交回放受理号() {
        AtomicInteger executions = new AtomicInteger();
        AcceptanceService.Ticket first = service.submit(command("k-2"), () -> {
            executions.incrementAndGet();
            return new Created("SKU-2");
        });
        AcceptanceService.Ticket second = service.submit(command("k-2"), () -> {
            executions.incrementAndGet();
            return new Created("SKU-2");
        });

        assertThat(second.acceptNo()).isEqualTo(first.acceptNo());
        assertThat(executions.get()).isEqualTo(1);
        assertThat(acceptances.rows).hasSize(1);
    }

    @Test
    @DisplayName("同键异体按 IDEMPOTENT_KEY_CONFLICT 拒绝，首次受理记录不变")
    void 同键异体拒绝() {
        service.submit(command("k-3", "body-a"), () -> new Created("SKU-3"));

        assertThatThrownBy(() -> service.submit(command("k-3", "body-b"), () -> new Created("SKU-3")))
            .isInstanceOf(AcceptanceException.class)
            .extracting(e -> ((AcceptanceException) e).code())
            .isEqualTo(AcceptanceErrorCodes.IDEMPOTENT_KEY_CONFLICT);
        assertThat(acceptances.rows).hasSize(1);
    }

    @Test
    @DisplayName("占位后尚未落受理记录时重复请求返回可重试的处理中，并带重试间隔")
    void 处理中带重试间隔() {
        idempotencyRecords.reserve(SCOPE, API, "k-4", idempotencyFingerprint("body"), Instant.now(),
            Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> service.submit(command("k-4"), () -> new Created("SKU-4")))
            .isInstanceOf(AcceptanceException.class)
            .satisfies(e -> {
                AcceptanceException acceptance = (AcceptanceException) e;
                assertThat(acceptance.code()).isEqualTo(AcceptanceErrorCodes.IDEMPOTENT_IN_PROGRESS);
                assertThat(acceptance.retryAfterMs()).isPositive();
            });
    }

    @Test
    @DisplayName("业务失败：受理记录为失败并带契约错误码，重复请求仍回放同一受理号")
    void 失败带错误码() {
        AcceptanceService.Ticket ticket = service.submit(command("k-5"), () -> {
            throw new AcceptanceException("STATE_INVALID", "状态不允许");
        });

        AcceptanceService.View view = service.find(query(API), ticket.acceptNo());
        assertThat(view.acceptStatus()).isEqualTo(AcceptanceStatus.FAILED.name());
        assertThat(view.errorCode()).isEqualTo("STATE_INVALID");
        assertThat(view.result()).isNull();

        AcceptanceService.Ticket replay = service.submit(command("k-5"), () -> new Created("X"));
        assertThat(replay.acceptNo()).isEqualTo(ticket.acceptNo());
        assertThat(acceptances.rows).hasSize(1);
    }

    @Test
    @DisplayName("受理号不在数据域内按给定错误码拒绝")
    void 越域查询拒绝() {
        AcceptanceService.Ticket ticket = service.submit(command("k-6"), () -> new Created("SKU-6"));

        assertThatThrownBy(() -> service.find(
            new AcceptanceQuery("777", Set.of(API), NOT_FOUND), ticket.acceptNo()))
            .isInstanceOf(AcceptanceException.class)
            .extracting(e -> ((AcceptanceException) e).code())
            .isEqualTo(NOT_FOUND);
    }

    @Test
    @DisplayName("接口号隔离：另一业务的受理号在本业务的接口号集合内查不到")
    void 跨业务不可见() {
        AcceptanceService.Ticket ownerTicket = service.submit(
            new AcceptanceCommand(SCOPE, OWNER_API, "k-7", "disable", "body", "ACTIVE", 77L, 42L),
            () -> new Created("OW000001"));

        // 商品档案的查询集合（API-001 至 API-020）拿不到货主档案的受理单，按不存在同一响应。
        assertThatThrownBy(() -> service.find(query(API), ownerTicket.acceptNo()))
            .isInstanceOf(AcceptanceException.class)
            .extracting(e -> ((AcceptanceException) e).code())
            .isEqualTo(NOT_FOUND);
        // 本业务的查询集合能看到它。
        assertThat(service.find(query(OWNER_API), ownerTicket.acceptNo()).acceptNo())
            .isEqualTo(ownerTicket.acceptNo());
    }

    @Test
    @DisplayName("目标货主随受理记录落库：改档带目标货主，商品档案的受理单为空")
    void 目标货主落库() {
        AcceptanceService.Ticket ownerTicket = service.submit(
            new AcceptanceCommand(SCOPE, OWNER_API, "k-8", "edit", "body", "ACTIVE", 77L, 42L),
            () -> new Created("OW000001"));
        AcceptanceService.Ticket archiveTicket = service.submit(command("k-9"),
            () -> new Created("SKU-9"));

        assertThat(acceptances.byNo(ownerTicket.acceptNo()).subjectOwnerId()).isEqualTo(42L);
        assertThat(acceptances.byNo(archiveTicket.acceptNo()).subjectOwnerId()).isNull();
    }

    @Test
    @DisplayName("幂等键放宽到 128 位，字符集外或超长的键按参数错误拒绝")
    void 幂等键格式() {
        AcceptanceService.Ticket ticket = service.submit(command("a".repeat(128)),
            () -> new Created("SKU-10"));
        assertThat(ticket.acceptNo()).isNotBlank();

        assertThatThrownBy(() -> service.submit(command("a".repeat(129)), () -> new Created("X")))
            .isInstanceOf(AcceptanceException.class)
            .extracting(e -> ((AcceptanceException) e).code())
            .isEqualTo(AcceptanceErrorCodes.PARAM_INVALID);
        assertThatThrownBy(() -> service.submit(command("含中文的键"), () -> new Created("X")))
            .isInstanceOf(AcceptanceException.class)
            .extracting(e -> ((AcceptanceException) e).code())
            .isEqualTo(AcceptanceErrorCodes.PARAM_INVALID);
    }

    private AcceptanceQuery query(String apiCode) {
        return new AcceptanceQuery(SCOPE, Set.of(apiCode), NOT_FOUND);
    }

    private AcceptanceCommand command(String key) {
        return command(key, "body");
    }

    private AcceptanceCommand command(String key, String body) {
        return new AcceptanceCommand(SCOPE, API, key, "create", body, "DRAFT", 77L, null);
    }

    private String idempotencyFingerprint(String body) {
        return new IdempotencyService(idempotencyRecords).fingerprint(API, "create", body);
    }

    /** 受理结果摘要的替身：字段与 {@code ArchiveCreatedView} 对齐最小集。 */
    private record Created(String archiveId) {
    }

    /** 同步执行器：把异步执行压成同一线程，便于断言写序。 */
    private static final class DirectExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    /** 事务管理器替身：内存仓储没有事务语义，只保证 TransactionTemplate 可运行。 */
    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private static final class FakeAcceptanceRepository implements AcceptanceRepository {

        private final List<Acceptance> rows = new ArrayList<>();

        @Override
        public Acceptance findByAcceptNo(String acceptNo, Set<String> apiCodes,
                                         Set<Long> subjectOwnerIds) {
            return rows.stream()
                .filter(row -> row.acceptNo().equals(acceptNo))
                .filter(row -> apiCodes.contains(row.apiCode()))
                .filter(row -> inSubjectScope(row, subjectOwnerIds))
                .findFirst().orElse(null);
        }

        /** null 表示不按目标货主收窄；空集合表示一条都不可见（不回落成全量）。 */
        private boolean inSubjectScope(Acceptance row, Set<Long> subjectOwnerIds) {
            if (subjectOwnerIds == null) {
                return true;
            }
            return row.subjectOwnerId() != null && subjectOwnerIds.contains(row.subjectOwnerId());
        }

        @Override
        public long countProcessing(Set<String> apiCodes) {
            return rows.stream()
                .filter(row -> row.status() == AcceptanceStatus.PROCESSING)
                .filter(row -> apiCodes.contains(row.apiCode()))
                .count();
        }

        @Override
        public Acceptance findByIdempotency(String scopeKey, String apiCode,
                                            String idempotencyKey) {
            return rows.stream()
                .filter(row -> row.scopeKey().equals(scopeKey) && row.apiCode().equals(apiCode)
                    && row.idempotencyKey().equals(idempotencyKey))
                .findFirst().orElse(null);
        }

        @Override
        public void insert(Acceptance acceptance) {
            rows.removeIf(row -> row.acceptNo().equals(acceptance.acceptNo()));
            rows.add(acceptance);
        }

        @Override
        public void markSucceeded(String acceptNo, String resultJson, Instant now) {
            replace(byNo(acceptNo).succeeded(resultJson, now));
        }

        @Override
        public void markFailed(String acceptNo, String errorCode, String errorMessage, Instant now) {
            replace(byNo(acceptNo).failed(errorCode, errorMessage, now));
        }

        Acceptance byNo(String acceptNo) {
            return rows.stream().filter(row -> row.acceptNo().equals(acceptNo)).findFirst()
                .orElseThrow();
        }

        private void replace(Acceptance acceptance) {
            rows.replaceAll(row -> row.acceptNo().equals(acceptance.acceptNo()) ? acceptance : row);
        }
    }

    private static final class FakeIdempotencyRepository implements IdempotencyRepository {

        private final Map<String, IdempotencyRecord> rows = new HashMap<>();

        @Override
        public IdempotencyRecord find(String scopeKey, String apiCode, String idempotencyKey) {
            return rows.get(scopeKey + "|" + apiCode + "|" + idempotencyKey);
        }

        @Override
        public boolean reserve(String scopeKey, String apiCode, String idempotencyKey,
                               String fingerprint, Instant createdAt, Instant expiresAt) {
            String key = scopeKey + "|" + apiCode + "|" + idempotencyKey;
            if (rows.containsKey(key)) {
                return false;
            }
            rows.put(key, new IdempotencyRecord(fingerprint, IdempotencyStatus.IN_PROGRESS, null,
                expiresAt));
            return true;
        }

        @Override
        public void markSucceeded(String scopeKey, String apiCode, String idempotencyKey,
                                  String resultJson) {
            String key = scopeKey + "|" + apiCode + "|" + idempotencyKey;
            IdempotencyRecord record = rows.get(key);
            rows.put(key, new IdempotencyRecord(record.fingerprint(), IdempotencyStatus.SUCCEEDED,
                resultJson, record.expiresAt()));
        }

        @Override
        public void release(String scopeKey, String apiCode, String idempotencyKey) {
            rows.remove(scopeKey + "|" + apiCode + "|" + idempotencyKey);
        }
    }
}
