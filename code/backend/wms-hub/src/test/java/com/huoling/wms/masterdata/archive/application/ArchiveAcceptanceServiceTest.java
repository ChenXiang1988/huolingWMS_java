package com.huoling.wms.masterdata.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.huoling.wms.masterdata.archive.domain.AcceptanceStatus;
import com.huoling.wms.masterdata.archive.domain.ArchiveAcceptance;
import com.huoling.wms.masterdata.archive.domain.ArchiveAcceptanceRepository;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchiveIdempotencyRepository;
import com.huoling.wms.masterdata.archive.domain.IdempotencyRecord;
import com.huoling.wms.masterdata.archive.domain.IdempotencyStatus;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受理闭环（tasks 1.9）：写接口只回受理号，受理查询覆盖处理中、成功与失败三态。
 *
 * <p>用内存仓储替身加同步执行器，验证四件事：首次受理落一条记录、重复提交回放同一受理号、
 * 同键异体拒绝、业务失败留下带错误码的受理记录。事务本身由数据库保证，这里验证写序与判定。
 */
class ArchiveAcceptanceServiceTest {

    private static final String API = "API-001";
    private static final String SCOPE = "9";

    private FakeAcceptanceRepository acceptances;
    private FakeIdempotencyRepository idempotencyRecords;
    private ArchiveAcceptanceService service;

    @BeforeEach
    void setUp() {
        acceptances = new FakeAcceptanceRepository();
        idempotencyRecords = new FakeIdempotencyRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ArchiveAcceptanceService(acceptances,
            new ArchiveIdempotencyService(idempotencyRecords), new ArchiveNumbers(), objectMapper,
            new TransactionTemplate(new NoopTransactionManager()), new DirectExecutor());
    }

    @Test
    @DisplayName("首次受理：返回受理号与状态快照，执行成功后受理记录为成功并带结果摘要")
    void 首次受理并成功() {
        ArchiveAcceptanceService.Ticket ticket = service.submit(command("k-1"), () -> new Created("SKU-1"));

        assertThat(ticket.acceptNo()).startsWith("ARC");
        assertThat(ticket.status()).isEqualTo("DRAFT");
        ArchiveAcceptanceService.View view = service.find(SCOPE, ticket.acceptNo());
        assertThat(view.acceptStatus()).isEqualTo(AcceptanceStatus.SUCCEEDED.name());
        assertThat(view.result().get("archiveId").asText()).isEqualTo("SKU-1");
        assertThat(view.dataTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("重复提交只产生一条受理记录，回放首次受理号且不重复执行")
    void 重复提交回放受理号() {
        AtomicInteger executions = new AtomicInteger();
        ArchiveAcceptanceService.Ticket first = service.submit(command("k-2"), () -> {
            executions.incrementAndGet();
            return new Created("SKU-2");
        });
        ArchiveAcceptanceService.Ticket second = service.submit(command("k-2"), () -> {
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
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.IDEMPOTENT_KEY_CONFLICT);
        assertThat(acceptances.rows).hasSize(1);
    }

    @Test
    @DisplayName("占位后尚未落受理记录时重复请求返回可重试的处理中，并带重试间隔")
    void 处理中带重试间隔() {
        idempotencyRecords.reserve(SCOPE, API, "k-4", idempotencyFingerprint("body"), Instant.now(),
            Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> service.submit(command("k-4"), () -> new Created("SKU-4")))
            .isInstanceOf(ArchiveException.class)
            .satisfies(e -> {
                ArchiveException archive = (ArchiveException) e;
                assertThat(archive.code()).isEqualTo(ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS);
                assertThat(archive.retryAfterMs()).isPositive();
            });
    }

    @Test
    @DisplayName("业务失败：受理记录为失败并带契约错误码，重复请求仍回放同一受理号")
    void 失败带错误码() {
        ArchiveAcceptanceService.Ticket ticket = service.submit(command("k-5"), () -> {
            throw new ArchiveException(ArchiveErrorCodes.STATE_INVALID, "状态不允许");
        });

        ArchiveAcceptanceService.View view = service.find(SCOPE, ticket.acceptNo());
        assertThat(view.acceptStatus()).isEqualTo(AcceptanceStatus.FAILED.name());
        assertThat(view.errorCode()).isEqualTo(ArchiveErrorCodes.STATE_INVALID);
        assertThat(view.result()).isNull();

        ArchiveAcceptanceService.Ticket replay = service.submit(command("k-5"), () -> new Created("X"));
        assertThat(replay.acceptNo()).isEqualTo(ticket.acceptNo());
        assertThat(acceptances.rows).hasSize(1);
    }

    @Test
    @DisplayName("受理号不在数据域内按 NOT_FOUND_ARCHIVE 拒绝")
    void 越域查询拒绝() {
        ArchiveAcceptanceService.Ticket ticket = service.submit(command("k-6"), () -> new Created("SKU-6"));

        assertThatThrownBy(() -> service.find("777", ticket.acceptNo()))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.NOT_FOUND_ARCHIVE);
    }

    private ArchiveAcceptanceService.Command command(String key) {
        return command(key, "body");
    }

    private ArchiveAcceptanceService.Command command(String key, String body) {
        return new ArchiveAcceptanceService.Command(SCOPE, API, key, "create", body, "DRAFT", 77L);
    }

    private String idempotencyFingerprint(String body) {
        return new ArchiveIdempotencyService(idempotencyRecords).fingerprint(API, "create", body);
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

    private static final class FakeAcceptanceRepository implements ArchiveAcceptanceRepository {

        private final java.util.List<ArchiveAcceptance> rows = new java.util.ArrayList<>();

        @Override
        public ArchiveAcceptance findByAcceptNo(String acceptNo) {
            return rows.stream().filter(row -> row.acceptNo().equals(acceptNo)).findFirst().orElse(null);
        }

        @Override
        public ArchiveAcceptance findByIdempotency(String scopeKey, String apiCode,
                                                   String idempotencyKey) {
            return rows.stream()
                .filter(row -> row.scopeKey().equals(scopeKey) && row.apiCode().equals(apiCode)
                    && row.idempotencyKey().equals(idempotencyKey))
                .findFirst().orElse(null);
        }

        @Override
        public void insert(ArchiveAcceptance acceptance) {
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

        private ArchiveAcceptance byNo(String acceptNo) {
            return rows.stream().filter(row -> row.acceptNo().equals(acceptNo)).findFirst()
                .orElseThrow();
        }

        private void replace(ArchiveAcceptance acceptance) {
            rows.replaceAll(row -> row.acceptNo().equals(acceptance.acceptNo()) ? acceptance : row);
        }
    }

    private static final class FakeIdempotencyRepository implements ArchiveIdempotencyRepository {

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
