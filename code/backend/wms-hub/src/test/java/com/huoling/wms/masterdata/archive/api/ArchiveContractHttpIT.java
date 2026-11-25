package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三端契约逐项对账（本次变更 design「API 接口契约」与「契约验收」）：
 * 路径、HTTP 状态、错误码、错误体四个必填字段、写接口 202 只回受理号与状态快照、
 * 幂等键读 {@code Idempotency-Key} 头、响应字段一律用契约英文名。
 *
 * <p>走真实 HTTP 与真实数据库：过滤器、权限拦截器、异常映射与 JSON 序列化都在链路上，
 * 不 mock 任何一层；把路径前缀、状态码或字段名改错，用例即变红。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArchiveContractHttpIT extends ArchiveDbTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenService tokenService;

    @Test
    @DisplayName("API-001 货主端创建草稿：功能权限 archive.owner.write 放行并返回 202 与受理号")
    void 货主端创建草稿契约() {
        long ownerId = registerOwner();
        String token = ownerToken(createOwnerAccount(ownerId));
        String key = "http-owner-create-" + nextId();
        registerIdempotencyKey(key);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/owner/archives", token,
            key, archiveBody(null, "T-H-1", "6900000004001", "矿泉水"));

        assertThat(response.getStatusCode())
            .as("货主用户拥有 archive.owner.write，创建草稿必须被放行：响应体 %s", response.getBody())
            .isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> data = data(response);
        assertThat(data.get("acceptNo")).as("受理号是字符串标识").isInstanceOf(String.class);
        assertThat(data.get("acceptNo").toString()).isNotBlank();
        assertThat(data.get("status")).isEqualTo("DRAFT");
        assertThat(data).as("写接口不返回最终版本号").doesNotContainKey("version");
        registerAcceptNo(data.get("acceptNo").toString());
    }

    @Test
    @DisplayName("API-006 运营代建：202 只回受理号与状态快照，归属为被指定货主")
    void 运营代建契约() {
        long ownerId = registerOwner();
        String hubToken = hubToken(createHubAdminAccount());
        String key = "http-hub-create-" + nextId();
        registerIdempotencyKey(key);

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, "/api/hub/archives",
            hubToken, key, archiveBody(ownerId, "T-H-2", "6900000004002", "代建档案"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> accepted = data(created);
        assertThat(accepted.get("acceptNo")).isInstanceOf(String.class);
        assertThat(accepted.get("acceptNo").toString()).isNotBlank();
        assertThat(accepted.get("status")).isEqualTo("DRAFT");
        assertThat(accepted).as("写接口不返回执行结果").doesNotContainKey("archiveId");
        String acceptNo = accepted.get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        Map<String, Object> acceptance = awaitAcceptance("/api/hub/archive-acceptances/" + acceptNo,
            hubToken, "SUCCEEDED");
        @SuppressWarnings("unchecked")
        String archiveId = ((Map<String, Object>) acceptance.get("result")).get("archiveId").toString();

        assertThat(jdbc.queryForObject("select owner_id from hub_sku_archive where archive_no = ?",
            Long.class, archiveId)).as("运营代建不改变归属").isEqualTo(ownerId);
    }

    @Test
    @DisplayName("API-007 列表逐字段使用契约英文名，含游标、是否还有下一页与数据时间戳")
    void 列表字段契约() {
        long ownerId = registerOwner();
        commandService.createDraft(ownerId, fields("T-H-3", "6900000004003", "绿茶"), 0L);
        String hubToken = hubToken(createHubAdminAccount());

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/api/hub/archives?ownerId=" + ownerId + "&sortBy=name&sortDir=asc&limit=20",
            hubToken, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = data(response);
        assertThat(data).containsKeys("items", "nextCursor", "hasMore", "dataTimestamp");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertThat(items).isNotEmpty();
        assertThat(items.get(0)).containsKeys("archiveId", "ownerId", "ownerCode", "name", "barcode",
            "baseUnit", "storageCondition", "spec", "category", "status", "version");
        assertThat(items.get(0).get("ownerId")).as("标识一律十进制字符串").isInstanceOf(String.class);
        assertThat(items.get(0).get("version")).as("版本号是整数").isInstanceOf(Number.class);
    }

    @Test
    @DisplayName("API-016 受理查询从处理中走到成功，返回受理状态、结果摘要与数据时间戳")
    void 受理查询契约() {
        long ownerId = registerOwner();
        String hubToken = hubToken(createHubAdminAccount());
        String key = "http-acceptance-" + nextId();
        registerIdempotencyKey(key);

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, "/api/hub/archives",
            hubToken, key, archiveBody(ownerId, "T-H-4", "6900000004004", "纯净水"));
        String acceptNo = data(created).get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        Map<String, Object> acceptance = awaitAcceptance("/api/hub/archive-acceptances/" + acceptNo,
            hubToken, "SUCCEEDED");
        assertThat(acceptance).containsKeys("acceptNo", "acceptStatus", "result", "dataTimestamp");
        assertThat(acceptance.get("acceptStatus")).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) acceptance.get("result");
        assertThat(result.get("archiveId")).as("结果摘要里的档案编号是字符串标识")
            .isInstanceOf(String.class);
    }

    @Test
    @DisplayName("API-008 审核 202 只回受理时状态快照，执行结论在 API-016 里")
    void 审核契约() {
        long ownerId = registerOwner();
        String archiveNo = commandService.createDraft(ownerId,
            fields("T-H-5", "6900000004005", "红茶"), 0L).archiveId();
        commandService.submit(archiveNo, ownerId, 0L, 0L);
        String hubToken = hubToken(createHubAdminAccount());
        String key = "http-review-" + nextId();
        registerIdempotencyKey(key);

        ResponseEntity<Map<String, Object>> reviewed = call(HttpMethod.POST,
            "/api/hub/archives/" + archiveNo + "/review", hubToken, key,
            "{\"action\":\"APPROVE\"}");

        assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> accepted = data(reviewed);
        assertThat(accepted.get("status")).as("受理时状态快照为待审核").isEqualTo("PENDING");
        String acceptNo = accepted.get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        Map<String, Object> outcome = awaitAcceptance("/api/hub/archive-acceptances/" + acceptNo,
            hubToken, "SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) outcome.get("result");
        assertThat(result.get("status")).as("执行结论在受理结果里").isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from hub_sku_archive where archive_no = ?",
            String.class, archiveNo)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("API-009 批量：202 受理，逐条结果由 API-016 返回且条数与输入一致")
    void 批量逐条结果契约() {
        long ownerId = registerOwner();
        String first = commandService.createDraft(ownerId, fields("T-H-6", "6900000004006", "水 A"), 0L)
            .archiveId();
        String second = commandService.createDraft(ownerId, fields("T-H-7", "6900000004007", "水 B"), 0L)
            .archiveId();
        commandService.submit(first, ownerId, 0L, 0L);
        // second 保持草稿：批量里应逐条失败，不影响 first
        String hubToken = hubToken(createHubAdminAccount());
        String key = "http-batch-" + nextId();
        registerIdempotencyKey(key);

        String requestBody = "{\"archiveIds\":[\"" + first + "\",\"" + second + "\"],"
            + "\"action\":\"APPROVE\"}";
        ResponseEntity<Map<String, Object>> batched = call(HttpMethod.POST, "/api/hub/archives/batch",
            hubToken, key, requestBody);

        assertThat(batched.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = data(batched).get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        Map<String, Object> outcome = awaitAcceptance("/api/hub/archive-acceptances/" + acceptNo,
            hubToken, "SUCCEEDED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
            (List<Map<String, Object>>) ((Map<String, Object>) outcome.get("result")).get("items");

        assertThat(items).as("结果条数必须与输入一致").hasSize(2);
        assertThat(items.get(0)).containsKeys("archiveId", "success", "status", "errorCode", "message");
        Map<String, Object> success = items.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("success"))).findFirst().orElseThrow();
        Map<String, Object> failure = items.stream()
            .filter(item -> Boolean.FALSE.equals(item.get("success"))).findFirst().orElseThrow();
        assertThat(success.get("archiveId")).isEqualTo(first);
        assertThat(failure.get("errorCode")).isEqualTo(ArchiveErrorCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("缺少 Idempotency-Key 头返回 400 PARAM_INVALID，错误体四个必填字段齐全")
    void 缺幂等键契约() {
        long ownerId = registerOwner();
        String hubToken = hubToken(createHubAdminAccount());

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/hub/archives", hubToken,
            null, archiveBody(ownerId, "T-H-8", "6900000004008", "苏打水"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> payload = response.getBody();
        assertThat(payload.get("code")).isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
        assertThat(payload.get("message")).isNotNull();
        assertThat(payload.get("traceId")).isNotNull();
        assertThat(payload.get("retryable")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("请求体含未知字段返回 400 PARAM_INVALID")
    void 未知字段契约() {
        long ownerId = registerOwner();
        String hubToken = hubToken(createHubAdminAccount());
        String key = "http-unknown-" + nextId();

        String withUnknownField = "{\"ownerId\":\"" + ownerId + "\",\"ownerCode\":\"T-H-9\","
            + "\"barcode\":\"6900000004009\",\"name\":\"气泡水\",\"baseUnit\":\"瓶\","
            + "\"storageCondition\":\"常温\",\"locationId\":\"L-01\"}";
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/hub/archives",
            hubToken, key, withUnknownField);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("非法排序字段落到 400 PARAM_INVALID，不是 500，也不在错误码表外新造码")
    void 排序参数与错误码闭环契约() {
        String hubToken = hubToken(createHubAdminAccount());

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/api/hub/archives?sortBy=barcode", hubToken, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("受理号不存在或不在数据域内返回 404 NOT_FOUND_ARCHIVE")
    void 受理号不存在契约() {
        String hubToken = hubToken(createHubAdminAccount());

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/api/hub/archive-acceptances/ARC000000000000000000", hubToken, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveErrorCodes.NOT_FOUND_ARCHIVE);
    }

    @Test
    @DisplayName("端之间路径不混用：货主令牌打运营端接口被拒")
    void 端路径不混用契约() {
        long ownerId = registerOwner();
        String token = ownerToken(createOwnerAccount(ownerId));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, "/api/hub/archives", token,
            null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String ownerToken(long accountId) {
        return "Bearer " + tokenService.issue(accountId, ClientType.OWNER);
    }

    private String hubToken(long accountId) {
        return "Bearer " + tokenService.issue(accountId, ClientType.HUB);
    }

    private String archiveBody(Long ownerId, String ownerCode, String barcode, String name) {
        String ownerPart = ownerId == null ? "" : "\"ownerId\":\"" + ownerId + "\",";
        return "{" + ownerPart + "\"ownerCode\":\"" + ownerCode + "\",\"barcode\":\"" + barcode + "\","
            + "\"name\":\"" + name + "\",\"baseUnit\":\"瓶\",\"storageCondition\":\"常温\","
            + "\"spec\":\"550ml\",\"category\":\"饮料\"}";
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token,
                                                     String idempotencyKey, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<String> entity = new HttpEntity<>(json, headers);
        return restTemplate.exchange("http://localhost:" + port + path, method, entity,
            new ParameterizedTypeReference<Map<String, Object>>() {
            });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
            .as("期望 2xx，实际 %s，响应体 %s", response.getStatusCode(), response.getBody()).isTrue();
        Map<String, Object> payload = response.getBody();
        assertThat(payload).isNotNull();
        return (Map<String, Object>) payload.get("data");
    }

    /** 轮询受理查询到指定状态：按状态等，不用固定 sleep 赌异步完成时间。 */
    private Map<String, Object> awaitAcceptance(String path, String token, String expected) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        Map<String, Object> last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, path, token, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            last = data(response);
            if (expected.equals(last.get("acceptStatus"))) {
                return last;
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("受理查询未在 " + WAIT + " 内进入 " + expected + "，最后响应 " + last);
    }
}
