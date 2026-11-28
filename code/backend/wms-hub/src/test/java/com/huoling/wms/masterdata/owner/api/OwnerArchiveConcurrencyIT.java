package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同键并发（tasks 1.10 的验证口径、契约 1.3「并发一致」）：十次同键并发只产生
 * 一条档案、一条状态留痕、一个受理号；处理中的并发请求返回可重试错误。
 *
 * <p>为什么必须打真库与真 HTTP：幂等的串行化点是数据库唯一键（{@code hub_idempotency} 与
 * {@code uk_wms_owner_credit_code}），内存替身测不出「后到者被索引挡住」的行为；
 * 受理是异步的，也只有真线程池才能暴露「同一键被执行两次」。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerArchiveConcurrencyIT extends OwnerApiTestSupport {

    private static final int CONCURRENCY = 10;

    @Test
    @DisplayName("十次同键并发建档：一个受理号、一条档案、重复请求 replayed=true 或处理中")
    void 同键并发建档() throws Exception {
        String token = hubToken(createHubAdminAccount());
        String key = newKey("concurrent-create");
        OwnerFields fields = fields("并发建档货主");
        String body = "{\"ownerName\":\"" + fields.ownerName() + "\",\"contactName\":\""
            + fields.contactName() + "\",\"contactPhone\":\"" + fields.contactPhone()
            + "\",\"creditCode\":\"" + fields.creditCode() + "\"}";

        List<ResponseEntity<Map<String, Object>>> responses = inParallel(
            () -> call(HttpMethod.POST, "/api/hub/owner-archives", token, key, body));

        Set<String> acceptNos = new HashSet<>();
        int accepted = 0;
        int inProgress = 0;
        for (ResponseEntity<Map<String, Object>> response : responses) {
            if (response.getStatusCode().value() == 202) {
                accepted++;
                acceptNos.add(data(response).get("acceptNo").toString());
            } else {
                assertThat(response.getBody().get("code"))
                    .as("并发落败者只能是可重试的处理中")
                    .isEqualTo(OwnerErrorCodes.IDEMPOTENT_IN_PROGRESS);
                assertThat(response.getBody().get("retryable")).isEqualTo(Boolean.TRUE);
                inProgress++;
            }
        }
        assertThat(accepted).isPositive();
        assertThat(accepted + inProgress).isEqualTo(CONCURRENCY);
        assertThat(acceptNos).as("十次同键只对应一个受理号").hasSize(1);

        String acceptNo = acceptNos.iterator().next();
        registerAcceptNo(acceptNo);
        Map<String, Object> result = resultOf(awaitAcceptance(acceptNo, token, "SUCCEEDED"));
        registerOwnerRow(Long.parseLong(result.get("ownerId").toString()));

        assertThat(jdbc.queryForObject("select count(*) from wms_owner where credit_code = ?",
            Integer.class, fields.creditCode())).as("只产生一条档案").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from hub_archive_acceptance where idempotency_key = ?",
            Integer.class, key)).as("只产生一条受理单").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from wms_owner where credit_code = ? and status_action = 'CREATE'",
            Integer.class, fields.creditCode())).as("状态留痕只一条").isEqualTo(1);
    }

    @Test
    @DisplayName("十次同键并发停用：只停用一次，版本只前进一次，留痕只一条")
    void 同键并发停用() throws Exception {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("并发停用货主");
        String createKey = newKey("concurrent-disable-create");
        String createAcceptNo = data(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            createKey, "{\"ownerName\":\"" + fields.ownerName() + "\",\"contactName\":\""
                + fields.contactName() + "\",\"contactPhone\":\"" + fields.contactPhone()
                + "\",\"creditCode\":\"" + fields.creditCode() + "\"}"))
            .get("acceptNo").toString();
        registerAcceptNo(createAcceptNo);
        Map<String, Object> created = resultOf(awaitAcceptance(createAcceptNo, token, "SUCCEEDED"));
        registerOwnerRow(Long.parseLong(created.get("ownerId").toString()));
        String ownerNo = created.get("ownerNo").toString();

        String disableKey = newKey("concurrent-disable");
        String disableBody = "{\"reason\":\"并发停用\",\"version\":0}";
        List<ResponseEntity<Map<String, Object>>> responses = inParallel(
            () -> call(HttpMethod.POST, "/api/hub/owner-archives/" + ownerNo + "/disable", token,
                disableKey, disableBody));

        Set<String> acceptNos = new HashSet<>();
        for (ResponseEntity<Map<String, Object>> response : responses) {
            if (response.getStatusCode().value() == 202) {
                acceptNos.add(data(response).get("acceptNo").toString());
            } else {
                assertThat(response.getBody().get("code"))
                    .isEqualTo(OwnerErrorCodes.IDEMPOTENT_IN_PROGRESS);
            }
        }
        assertThat(acceptNos).hasSize(1);
        String disableAcceptNo = acceptNos.iterator().next();
        registerAcceptNo(disableAcceptNo);

        Map<String, Object> result = resultOf(awaitAcceptance(disableAcceptNo, token, "SUCCEEDED"));
        assertThat(result.get("status")).isEqualTo("DISABLED");
        assertThat(result.get("version")).as("版本只前进一次").isEqualTo(1);
        assertThat(jdbc.queryForObject("select status_action from wms_owner where owner_no = ?",
            String.class, ownerNo)).isEqualTo("DISABLE");
        assertThat(jdbc.queryForObject(
            "select count(*) from hub_archive_acceptance where idempotency_key = ?",
            Integer.class, disableKey)).as("只产生一条受理单").isEqualTo(1);
    }

    /** 同时发 N 个请求：用闸门让它们尽量同一时刻出发。 */
    private List<ResponseEntity<Map<String, Object>>> inParallel(
            java.util.function.Supplier<ResponseEntity<Map<String, Object>>> request)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (int index = 0; index < CONCURRENCY; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.get();
                }));
            }
            start.countDown();
            List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private String newKey(String scene) {
        String key = "it-owner-concurrent-" + scene + "-" + nextId();
        registerIdempotencyKey(key);
        return key;
    }
}
