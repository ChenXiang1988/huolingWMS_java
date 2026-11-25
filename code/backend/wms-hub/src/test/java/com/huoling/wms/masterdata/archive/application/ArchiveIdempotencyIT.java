package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.domain.AcceptanceStatus;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等与异步受理（design「幂等与异步事件」、契约「幂等约定」「写接口统一响应」）：
 * 作用域为归属货主加接口加键，窗口不少于二十四小时；重复请求返回首次受理号，同键异体冲突，
 * 处理中返回可重试错误；受理查询从处理中走到成功或失败。
 *
 * <p>并发用闩锁同步起跑，不用 sleep 赌时序；断言落在库里的受理记录条数与业务动作执行次数上。
 * 把幂等唯一键或判定顺序改坏，这些断言立即变红。
 */
@SpringBootTest(classes = HubApplication.class)
class ArchiveIdempotencyIT extends ArchiveDbTestSupport {

    private static final String API = "API-001";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    @DisplayName("十次并发重复提交只产生一条受理记录与一次业务执行，受理号一致")
    void 十次并发重复() throws InterruptedException {
        long ownerId = registerOwner();
        String scope = String.valueOf(ownerId);
        String key = "concurrent-" + nextId();
        registerIdempotencyKey(key);

        AtomicInteger executions = new AtomicInteger();
        int threads = 10;
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        List<String> acceptNos = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int index = 0; index < threads; index++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        ArchiveAcceptanceService.Ticket ticket = acceptanceService.submit(
                            command(ownerId, key, "create", "body-same"),
                            () -> {
                                executions.incrementAndGet();
                                return new Created("SKU-CONCURRENT");
                            });
                        acceptNos.add(ticket.acceptNo());
                    } catch (Throwable e) {
                        failures.add(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(finished.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("十个并发请求应在 %s 内全部返回", WAIT).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 契约「幂等」：十次并发重复只产生一个业务结果，受理号一致
        assertThat(acceptNos).as("至少一个请求拿到受理号").isNotEmpty();
        assertThat(new HashSet<>(acceptNos)).as("受理号必须唯一").hasSize(1);

        String acceptNo = acceptNos.get(0);
        registerAcceptNo(acceptNo);

        assertThat(countAcceptances(scope, API, key)).as("受理记录只允许一条").isEqualTo(1);
        assertThat(countIdempotency(scope, API, key)).isEqualTo(1);

        awaitStatus(scope, acceptNo, AcceptanceStatus.SUCCEEDED);
        assertThat(executions.get()).as("业务动作只执行一次").isEqualTo(1);

        // 失败的并发请求只能是契约允许的「处理中」（可重试）；出现其它代码即契约外失败
        for (Throwable failure : failures) {
            assertThat(failure)
                .as("并发重复提交只允许回放受理号或返回 IDEMPOTENT_IN_PROGRESS，实际为 %s", failure)
                .isInstanceOf(ArchiveException.class);
            ArchiveException archive = (ArchiveException) failure;
            assertThat(archive.code()).isEqualTo(ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS);
            assertThat(archive.retryAfterMs()).isPositive();
        }

        // 完成后的重复提交回放首次受理号，不再执行
        ArchiveAcceptanceService.Ticket replay = acceptanceService.submit(
            command(ownerId, key, "create", "body-same"),
            () -> {
                executions.incrementAndGet();
                return new Created("SKU-CONCURRENT-2");
            });
        assertThat(replay.acceptNo()).isEqualTo(acceptNo);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("同键异体返回 IDEMPOTENT_KEY_CONFLICT，首次受理记录与首次受理号都不变")
    void 同键异体冲突() {
        long ownerId = registerOwner();
        String scope = String.valueOf(ownerId);
        String key = "conflict-" + nextId();
        registerIdempotencyKey(key);

        ArchiveAcceptanceService.Ticket first = acceptanceService.submit(
            command(ownerId, key, "create", "body-a"), () -> new Created("SKU-A"));
        registerAcceptNo(first.acceptNo());
        awaitStatus(scope, first.acceptNo(), AcceptanceStatus.SUCCEEDED);

        assertThatThrownBy(() -> acceptanceService.submit(
            command(ownerId, key, "create", "body-b"), () -> new Created("SKU-B")))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.IDEMPOTENT_KEY_CONFLICT);

        assertThat(countAcceptances(scope, API, key)).isEqualTo(1);
        ArchiveAcceptanceService.View view = acceptanceService.find(scope, first.acceptNo());
        assertThat(view.result().get("archiveId").asText()).isEqualTo("SKU-A");
    }

    @Test
    @DisplayName("幂等记录处理中且受理记录尚未落地时返回可重试的 IDEMPOTENT_IN_PROGRESS")
    void 处理中带重试间隔() {
        long ownerId = registerOwner();
        String scope = String.valueOf(ownerId);
        String key = "inflight-" + nextId();
        registerIdempotencyKey(key);

        // 直接造「已占位、受理记录未落地」的中间态：这一段是契约允许返回处理中的唯一窗口
        String fingerprint = idempotencyFingerprint("create", "body");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into hub_idempotency (id, scope_key, api_code, idempotency_key, fingerprint,
                status, result_json, error_code, created_at, expires_at)
            values (?, ?, ?, ?, ?, 'IN_PROGRESS', null, null, ?, ?)
            """, nextId(), scope, API, key, fingerprint, now, now.plusHours(24));

        assertThatThrownBy(() -> acceptanceService.submit(
            command(ownerId, key, "create", "body"), () -> new Created("SKU-WAIT")))
            .isInstanceOf(ArchiveException.class)
            .satisfies(e -> {
                ArchiveException archive = (ArchiveException) e;
                assertThat(archive.code()).isEqualTo(ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS);
                assertThat(archive.retryAfterMs()).isPositive();
            });
        assertThat(countAcceptances(scope, API, key)).isZero();
    }

    @Test
    @DisplayName("受理查询从处理中走到成功：执行期间为 PROCESSING，完成后带结果摘要")
    void 处理中到成功() throws InterruptedException {
        long ownerId = registerOwner();
        String scope = String.valueOf(ownerId);
        String key = "pending-" + nextId();
        registerIdempotencyKey(key);

        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        ArchiveAcceptanceService.Ticket ticket = acceptanceService.submit(
            command(ownerId, key, "create", "body-pending"), () -> {
                actionStarted.countDown();
                awaitQuietly(releaseAction);
                return new Created("SKU-PENDING");
            });
        registerAcceptNo(ticket.acceptNo());

        assertThat(ticket.status()).isEqualTo("DRAFT");
        assertThat(actionStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS))
            .as("业务动作应已被异步调度").isTrue();

        ArchiveAcceptanceService.View processing = acceptanceService.find(scope, ticket.acceptNo());
        assertThat(processing.acceptStatus()).isEqualTo(AcceptanceStatus.PROCESSING.name());
        assertThat(processing.result()).as("处理中不得伪造结果").isNull();
        assertThat(processing.dataTimestamp()).isNotNull();

        releaseAction.countDown();
        ArchiveAcceptanceService.View succeeded =
            awaitStatus(scope, ticket.acceptNo(), AcceptanceStatus.SUCCEEDED);
        assertThat(succeeded.result().get("archiveId").asText()).isEqualTo("SKU-PENDING");
        assertThat(succeeded.errorCode()).isNull();
    }

    @Test
    @DisplayName("受理查询从处理中走到失败：受理记录为 FAILED 并带契约错误码")
    void 处理中到失败() {
        long ownerId = registerOwner();
        String scope = String.valueOf(ownerId);
        String key = "failed-" + nextId();
        registerIdempotencyKey(key);

        ArchiveAcceptanceService.Ticket ticket = acceptanceService.submit(
            command(ownerId, key, "create", "body-failed"), () -> {
                throw new ArchiveException(ArchiveErrorCodes.RULE_DUPLICATE_CODE, "货主内编码重复");
            });
        registerAcceptNo(ticket.acceptNo());

        ArchiveAcceptanceService.View failed =
            awaitStatus(scope, ticket.acceptNo(), AcceptanceStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(ArchiveErrorCodes.RULE_DUPLICATE_CODE);
        assertThat(failed.result()).isNull();

        // 失败也算「首次受理已有结果」：重复请求回放同一受理号，不产生第二条受理记录
        ArchiveAcceptanceService.Ticket replay = acceptanceService.submit(
            command(ownerId, key, "create", "body-failed"), () -> new Created("SKU-OTHER"));
        assertThat(replay.acceptNo()).isEqualTo(ticket.acceptNo());
        assertThat(countAcceptances(scope, API, key)).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等窗口不少于二十四小时")
    void 幂等窗口不少于二十四小时() {
        long ownerId = registerOwner();
        String scope = String.valueOf(ownerId);
        String key = "window-" + nextId();
        registerIdempotencyKey(key);

        ArchiveAcceptanceService.Ticket ticket = acceptanceService.submit(
            command(ownerId, key, "create", "body-window"), () -> new Created("SKU-WINDOW"));
        registerAcceptNo(ticket.acceptNo());

        OffsetDateTime createdAt = jdbc.queryForObject(
            "select created_at from hub_idempotency where scope_key = ? and api_code = ? "
                + "and idempotency_key = ?", OffsetDateTime.class, scope, API, key);
        OffsetDateTime expiresAt = jdbc.queryForObject(
            "select expires_at from hub_idempotency where scope_key = ? and api_code = ? "
                + "and idempotency_key = ?", OffsetDateTime.class, scope, API, key);

        assertThat(Duration.between(createdAt, expiresAt)).isGreaterThanOrEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("受理号不在数据域内按 NOT_FOUND_ARCHIVE 拒绝，运营端全量视图可查")
    void 受理号按数据域隔离() {
        long ownerId = registerOwner();
        long otherOwner = registerOwner();
        String scope = String.valueOf(ownerId);
        String key = "scope-" + nextId();
        registerIdempotencyKey(key);

        ArchiveAcceptanceService.Ticket ticket = acceptanceService.submit(
            command(ownerId, key, "create", "body-scope"), () -> new Created("SKU-SCOPE"));
        registerAcceptNo(ticket.acceptNo());

        // 另一个货主按受理号查询：按目标不存在处理，不泄露受理内容
        assertThatThrownBy(() -> acceptanceService.find(String.valueOf(otherOwner), ticket.acceptNo()))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.NOT_FOUND_ARCHIVE);
        // 运营端全量视图（作用域为空）可查
        assertThat(acceptanceService.find(null, ticket.acceptNo()).acceptNo())
            .isEqualTo(ticket.acceptNo());
    }

    private ArchiveAcceptanceService.Command command(long ownerId, String key, String target,
                                                     String body) {
        return new ArchiveAcceptanceService.Command(String.valueOf(ownerId), API, key, target, body,
            "DRAFT", 0L);
    }

    private String idempotencyFingerprint(String target, String body) {
        return idempotencyService.fingerprint(API, target, body);
    }

    private int countAcceptances(String scope, String apiCode, String key) {
        return jdbc.queryForObject("select count(*) from hub_archive_acceptance where scope_key = ? "
            + "and api_code = ? and idempotency_key = ?", Integer.class, scope, apiCode, key);
    }

    private int countIdempotency(String scope, String apiCode, String key) {
        return jdbc.queryForObject("select count(*) from hub_idempotency where scope_key = ? "
            + "and api_code = ? and idempotency_key = ?", Integer.class, scope, apiCode, key);
    }

    /** 等待受理单进入指定状态：按状态轮询到截止时刻，不用固定 sleep 赌时序。 */
    private ArchiveAcceptanceService.View awaitStatus(String scope, String acceptNo, AcceptanceStatus expected) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            ArchiveAcceptanceService.View view = acceptanceService.find(scope, acceptNo);
            if (expected.name().equals(view.acceptStatus())) {
                return view;
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new AssertionError("受理单 " + acceptNo + " 未在 " + WAIT + " 内进入 " + expected);
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("等待执行放行信号超时").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 受理结果摘要的替身：字段与 {@code ArchiveCreatedView} 的最小集一致。 */
    private record Created(String archiveId) {
    }
}
