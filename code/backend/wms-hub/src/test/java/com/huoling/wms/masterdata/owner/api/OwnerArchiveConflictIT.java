package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发与幂等里尚未落地的四条（契约 1.2「并发」、1.3「幂等约定」）。
 *
 * <p>与既有 {@link OwnerArchiveConcurrencyIT} 的分工：那一类走「十次同键并发」，
 * 幂等去重先把重复请求挡在同一个受理单里；本类走的是「幂等没挡住之后」的三件事——
 * <ul>
 *   <li><b>不同幂等键</b>的并发冲突：两笔合法请求抢同一行，只有一笔成功、版本只前进一次；</li>
 *   <li><b>版本来回</b>：改档→停用→启用→改档的版本单调递增，任一旧版本重放都被拒；</li>
 *   <li><b>同键异体</b>在路径寻址接口上：指纹覆盖路径参数，换目标货主即冲突；</li>
 *   <li><b>处理中</b>：占位未完成时同键重复请求返回可重试错误，不新建档案。</li>
 * </ul>
 *
 * <p>全部连真库、打真 HTTP：乐观锁的「可比之处」是 {@code update ... where version = ?}
 * 的受影响行数，内存替身测不出来（替身里两笔永远都成功）。
 * 并发用闸门 {@link CountDownLatch} 起跑，不用 {@code Thread.sleep} 赌时序。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerArchiveConflictIT extends OwnerApiTestSupport {

    private static final int CONCURRENCY = 2;

    @Test
    @DisplayName("不同幂等键并发：同一档案同时被停用与改档，只有一笔成功，版本只前进一次")
    void 不同键并发只有一笔成功() throws Exception {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("并发冲突货主");
        String ownerNo = buildOwner(token, fields);

        String disableBody = "{\"reason\":\"并发停用\",\"version\":0}";
        String editBody = editBody(new OwnerFields("并发冲突货主（改名）", fields.contactName(),
            fields.contactPhone(), fields.creditCode()), 0L);

        List<Supplier<ResponseEntity<Map<String, Object>>>> requests = List.of(
            () -> call(HttpMethod.POST, "/api/hub/owner-archives/" + ownerNo + "/disable", token,
                newKey("conflict-disable"), disableBody),
            () -> call(HttpMethod.PUT, "/api/hub/owner-archives/" + ownerNo, token,
                newKey("conflict-edit"), editBody));

        List<Map<String, Object>> terminals = new ArrayList<>();
        for (String acceptNo : submitInParallel(token, requests)) {
            terminals.add(awaitTerminal(acceptNo, token));
        }

        long succeeded = terminals.stream()
            .filter(acceptance -> "SUCCEEDED".equals(acceptance.get("acceptStatus"))).count();
        assertThat(succeeded).as("两笔互斥动作只能有一笔成功，实际受理结果 %s", terminals)
            .isEqualTo(1);
        assertThat(terminals).filteredOn(acceptance -> "FAILED".equals(acceptance.get("acceptStatus")))
            .singleElement()
            .as("落败者是可重试的版本冲突，不是 500")
            .satisfies(failed -> assertThat(failed.get("errorCode"))
                .isEqualTo(OwnerErrorCodes.CONFLICT_VERSION));

        assertThat(jdbc.queryForObject("select version from wms_owner where owner_no = ?",
            Long.class, ownerNo)).as("版本只前进一次").isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from wms_owner where owner_no = ?",
            Integer.class, ownerNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("版本来回：改档→停用→启用→改档版本逐次递增，旧版本重放一律被拒、编号不变")
    void 版本来回版本单调递增() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields initial = fields("版本来回货主");
        String ownerNo = buildOwner(token, initial);

        Map<String, Object> edited = resultOf(awaitAcceptance(
            submit(token, HttpMethod.PUT, "/api/hub/owner-archives/" + ownerNo,
                newKey("cycle-edit-1"),
                editBody(names(initial, "版本来回货主-v1"), 0L)), token, "SUCCEEDED"));
        assertThat(edited.get("version")).isEqualTo(1);
        assertThat(edited.get("ownerNo")).as("改档不动编号").isEqualTo(ownerNo);

        Map<String, Object> disabled = resultOf(awaitAcceptance(
            submit(token, HttpMethod.POST, "/api/hub/owner-archives/" + ownerNo + "/disable",
                newKey("cycle-disable"), reasonBody("合同到期", 1L)), token, "SUCCEEDED"));
        assertThat(disabled.get("version")).isEqualTo(2);

        Map<String, Object> enabled = resultOf(awaitAcceptance(
            submit(token, HttpMethod.POST, "/api/hub/owner-archives/" + ownerNo + "/enable",
                newKey("cycle-enable"), reasonBody("合同续签", 2L)), token, "SUCCEEDED"));
        assertThat(enabled.get("version")).isEqualTo(3);
        assertThat(enabled.get("status")).isEqualTo("ACTIVE");

        Map<String, Object> editedAgain = resultOf(awaitAcceptance(
            submit(token, HttpMethod.PUT, "/api/hub/owner-archives/" + ownerNo,
                newKey("cycle-edit-2"),
                editBody(names(initial, "版本来回货主-v4"), 3L)), token, "SUCCEEDED"));
        assertThat(editedAgain.get("version")).isEqualTo(4);
        assertThat(editedAgain.get("ownerNo")).isEqualTo(ownerNo);

        // 旧版本重放：v0 已经过去四步，必须按可重试的版本冲突拒绝，而不是静默覆盖
        Map<String, Object> stale = awaitAcceptance(
            submit(token, HttpMethod.PUT, "/api/hub/owner-archives/" + ownerNo,
                newKey("cycle-stale"), editBody(names(initial, "旧版本覆盖"), 0L)), token, "FAILED");
        assertThat(stale.get("errorCode")).isEqualTo(OwnerErrorCodes.CONFLICT_VERSION);

        assertThat(jdbc.queryForObject("select version from wms_owner where owner_no = ?",
            Long.class, ownerNo)).as("失败的重放不推进版本").isEqualTo(4L);
        assertThat(jdbc.queryForObject("select owner_name from wms_owner where owner_no = ?",
            String.class, ownerNo)).as("版本落后者不得覆盖他人写入").isEqualTo("版本来回货主-v4");
    }

    @Test
    @DisplayName("同键异体（路径寻址）：同键同体换成另一个货主，409 IDEMPOTENT_KEY_CONFLICT")
    void 同键异体覆盖路径参数() {
        String token = hubToken(createHubAdminAccount());
        String firstOwner = buildOwner(token, fields("同键异体货主 A"));
        String secondOwner = buildOwner(token, fields("同键异体货主 B"));
        String key = newKey("conflict-target");

        String body = reasonBody("同一次表单提交", 0L);
        ResponseEntity<Map<String, Object>> first = call(HttpMethod.POST,
            "/api/hub/owner-archives/" + firstOwner + "/disable", token, key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        registerAcceptNo(data(first).get("acceptNo").toString());
        awaitAcceptance(data(first).get("acceptNo").toString(), token, "SUCCEEDED");

        ResponseEntity<Map<String, Object>> conflict = call(HttpMethod.POST,
            "/api/hub/owner-archives/" + secondOwner + "/disable", token, key, body);

        assertThat(conflict.getStatusCode())
            .as("指纹覆盖路径参数：换目标货主即同键异体，不能回放前一个货主的受理号")
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code"))
            .isEqualTo(OwnerErrorCodes.IDEMPOTENT_KEY_CONFLICT);
        assertThat(jdbc.queryForObject("select status from wms_owner where owner_no = ?",
            String.class, secondOwner)).as("首次结果不变，另一个货主未被停用").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("处理中：占位未完成时同键重复请求 409 IDEMPOTENT_IN_PROGRESS 并带 retryAfterMs")
    void 处理中返回可重试() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("处理中货主");
        String key = newKey("in-progress");
        String body = createBody(fields);

        ResponseEntity<Map<String, Object>> first = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = data(first).get("acceptNo").toString();
        registerAcceptNo(acceptNo);
        registerOwnerRow(Long.parseLong(
            resultOf(awaitAcceptance(acceptNo, token, "SUCCEEDED")).get("ownerId").toString()));

        // 造出「占位仍在处理中」的确定性状态：把幂等记录打回 IN_PROGRESS，并撤掉已完成的首个受理单。
        // 这是唯一能稳定复现的分支——首次受理一旦完成，重复请求就会回放受理号而不是处理中。
        jdbc.update("update hub_idempotency set status = 'IN_PROGRESS', result_json = null"
            + " where scope_key = 'HUB' and api_code = 'API-023' and idempotency_key = ?", key);
        jdbc.update("delete from hub_archive_acceptance where idempotency_key = ?", key);

        ResponseEntity<Map<String, Object>> inProgress = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, key, body);

        assertThat(inProgress.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inProgress.getBody().get("code"))
            .isEqualTo(OwnerErrorCodes.IDEMPOTENT_IN_PROGRESS);
        assertThat(inProgress.getBody().get("retryable")).isEqualTo(Boolean.TRUE);
        assertThat(inProgress.getBody().get("retryAfterMs")).as("处理中必须给退避间隔")
            .isInstanceOf(Number.class);
        assertThat(((Number) inProgress.getBody().get("retryAfterMs")).longValue())
            .as("退避最少一秒（契约 1.3）").isGreaterThanOrEqualTo(1000L);
        assertThat(jdbc.queryForObject("select count(*) from wms_owner where credit_code = ?",
            Integer.class, fields.creditCode())).as("处理中不得产生第二条档案").isEqualTo(1);
    }

    /** 并发提交同一批请求，返回各自的受理号。 */
    private List<String> submitInParallel(String token,
                                          List<Supplier<ResponseEntity<Map<String, Object>>>> requests)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (Supplier<ResponseEntity<Map<String, Object>>> request : requests) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.get();
                }));
            }
            start.countDown();
            List<String> acceptNos = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                ResponseEntity<Map<String, Object>> response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode())
                    .as("入口只做字段级校验，两笔都该 202 受理，实际 %s %s",
                        response.getStatusCode(), response.getBody())
                    .isEqualTo(HttpStatus.ACCEPTED);
                String acceptNo = data(response).get("acceptNo").toString();
                registerAcceptNo(acceptNo);
                acceptNos.add(acceptNo);
            }
            assertThat(new HashSet<>(acceptNos)).as("两个幂等键必须产生两个受理号")
                .hasSize(CONCURRENCY);
            return acceptNos;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 轮询到受理终态（成功或失败）；不假定谁先完成。 */
    private Map<String, Object> awaitTerminal(String acceptNo, String token) {
        long deadline = System.nanoTime() + ACCEPTANCE_WAIT.toNanos();
        Map<String, Object> last = null;
        while (System.nanoTime() < deadline) {
            last = data(call(HttpMethod.GET, "/api/hub/owner-archives/acceptances/" + acceptNo,
                token, null, null));
            if ("SUCCEEDED".equals(last.get("acceptStatus"))
                || "FAILED".equals(last.get("acceptStatus"))) {
                return last;
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("受理查询未在 " + ACCEPTANCE_WAIT + " 内收敛，最后响应 " + last);
    }

    private String submit(String token, HttpMethod method, String path, String key, String body) {
        ResponseEntity<Map<String, Object>> response = call(method, path, token, key, body);
        assertThat(response.getStatusCode()).as("写接口一律 202，实际 %s %s",
            response.getStatusCode(), response.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = data(response).get("acceptNo").toString();
        registerAcceptNo(acceptNo);
        return acceptNo;
    }

    private String buildOwner(String token, OwnerFields fields) {
        String acceptNo = submit(token, HttpMethod.POST, "/api/hub/owner-archives",
            newKey("build"), createBody(fields));
        Map<String, Object> result = resultOf(awaitAcceptance(acceptNo, token, "SUCCEEDED"));
        registerOwnerRow(Long.parseLong(result.get("ownerId").toString()));
        return result.get("ownerNo").toString();
    }

    /** 只换名称、其余三项沿用原值：改档是全量替换，缺一项会被入口按必填拒绝。 */
    private static OwnerFields names(OwnerFields base, String ownerName) {
        return new OwnerFields(ownerName, base.contactName(), base.contactPhone(), base.creditCode());
    }

    private String createBody(OwnerFields fields) {
        return "{\"ownerName\":" + json(fields.ownerName())
            + ",\"contactName\":" + json(fields.contactName())
            + ",\"contactPhone\":" + json(fields.contactPhone())
            + ",\"creditCode\":" + json(fields.creditCode()) + "}";
    }

    private String editBody(OwnerFields fields, Long version) {
        return "{" + createBody(fields).substring(1, createBody(fields).length() - 1)
            + ",\"version\":" + version + "}";
    }

    private String reasonBody(String reason, Long version) {
        return "{\"reason\":\"" + reason + "\",\"version\":" + version + "}";
    }

    private String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String newKey(String scene) {
        String key = "it-owner-conflict-" + scene + "-" + nextId();
        registerIdempotencyKey(key);
        return key;
    }
}
