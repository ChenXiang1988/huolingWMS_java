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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 货主档案写接口的契约验收（tasks 1.10、1.11）：202 受理票据、受理后的业务判定、
 * 幂等键与并发承诺、六十秒兜底，以及 API-027 的接口隔离。
 *
 * <p>全部走真实 HTTP 与真实数据库：路径、状态码、错误码、票据字段、结果摘要与库中行都在链路上，
 * 把受理时点、幂等键窗口或错误码改错，用例即变红。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerArchiveApiIT extends OwnerApiTestSupport {

    @Test
    @DisplayName("主链路 API-023 → API-027 → API-022：编号由系统生成，详情与受理结果一致")
    void 建档主链路() {
        long accountId = createHubAdminAccount();
        String token = hubToken(accountId);
        String key = newKey("create");
        OwnerFields fields = fields("华东仓储有限公司");

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, key, createBody(fields));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> ticket = data(created);
        assertThat(ticket).containsOnlyKeys("acceptNo", "status", "replayed");
        assertThat(ticket.get("status")).as("受理时快照为建档初始态").isEqualTo("ACTIVE");
        assertThat(ticket.get("replayed")).isEqualTo(Boolean.FALSE);
        String acceptNo = ticket.get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        Map<String, Object> acceptance = awaitAcceptance(acceptNo, token, "SUCCEEDED");
        assertThat(acceptance).as("成功响应只有受理状态与结果摘要，不带 dataTimestamp")
            .containsOnlyKeys("acceptStatus", "result");
        Map<String, Object> result = resultOf(acceptance);
        assertThat(result).containsOnlyKeys("ownerId", "ownerNo", "status", "version");
        assertThat(result.get("ownerId")).as("标识是十进制字符串").isInstanceOf(String.class);
        assertThat(result.get("status")).isEqualTo("ACTIVE");
        assertThat(result.get("version")).isEqualTo(0);
        String ownerNo = result.get("ownerNo").toString();
        assertThat(ownerNo).as("编号形态匹配 ^OW[A-Z0-9]{6,30}$").matches("^OW[A-Z0-9]{6,30}$");
        registerOwnerRow(Long.parseLong(result.get("ownerId").toString()));

        ResponseEntity<Map<String, Object>> detail = call(HttpMethod.GET,
            "/api/hub/owner-archives/" + ownerNo, token, null, null);
        Map<String, Object> body = data(detail);
        assertThat(body).containsKeys("ownerId", "ownerNo", "ownerName", "contactName",
            "contactPhone", "creditCode", "status", "version", "createdTime", "updateTime",
            "createdBy", "updatedBy", "lastStatusChange");
        assertThat(body).doesNotContainKey("dataTimestamp");
        assertThat(body.get("ownerNo")).isEqualTo(ownerNo);
        assertThat(body.get("ownerName")).isEqualTo(fields.ownerName());
        assertThat(body.get("createdBy")).as("审计列是字符串标识").isEqualTo(String.valueOf(accountId));
        @SuppressWarnings("unchecked")
        Map<String, Object> lastChange = (Map<String, Object>) body.get("lastStatusChange");
        assertThat(lastChange.get("action")).isEqualTo("CREATE");
        assertThat(lastChange.get("reason")).isNull();
        assertThat(lastChange.get("operator")).isNotNull();
        assertThat(lastChange.get("changedAt")).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("同键同体重复提交：同一受理号、replayed=true，库里只有一条档案")
    void 同键重复提交() {
        String token = hubToken(createHubAdminAccount());
        String key = newKey("replay");
        String body = createBody(fields("同键重复货主"));

        String first = data(call(HttpMethod.POST, "/api/hub/owner-archives", token, key, body))
            .get("acceptNo").toString();
        registerAcceptNo(first);
        Map<String, Object> second = data(call(HttpMethod.POST, "/api/hub/owner-archives",
            token, key, body));
        assertThat(second.get("acceptNo")).isEqualTo(first);
        assertThat(second.get("replayed")).as("第二次是同键重复").isEqualTo(Boolean.TRUE);

        Map<String, Object> result = resultOf(awaitAcceptance(first, token, "SUCCEEDED"));
        registerOwnerRow(Long.parseLong(result.get("ownerId").toString()));
        assertThat(jdbc.queryForObject("select count(*) from wms_owner where owner_no = ?",
            Integer.class, result.get("ownerNo").toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("同键异体：409 IDEMPOTENT_KEY_CONFLICT，首次结果不被覆盖")
    void 同键异体() {
        String token = hubToken(createHubAdminAccount());
        String key = newKey("conflict");
        String first = data(call(HttpMethod.POST, "/api/hub/owner-archives", token, key,
            createBody(fields("同键异体货主 A")))).get("acceptNo").toString();
        registerAcceptNo(first);

        ResponseEntity<Map<String, Object>> conflict = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, key, createBody(fields("同键异体货主 B")));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code"))
            .isEqualTo(OwnerErrorCodes.IDEMPOTENT_KEY_CONFLICT);
        assertThat(conflict.getBody().get("retryable")).isEqualTo(Boolean.FALSE);

        Map<String, Object> result = resultOf(awaitAcceptance(first, token, "SUCCEEDED"));
        registerOwnerRow(Long.parseLong(result.get("ownerId").toString()));
        assertThat(jdbc.queryForObject("select owner_name from wms_owner where owner_no = ?",
            String.class, result.get("ownerNo").toString())).isEqualTo("同键异体货主 A");
    }

    @Test
    @DisplayName("改档：编号不变、版本递增；版本落后在受理结果返回 CONFLICT_VERSION")
    void 改档与版本冲突() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields initial = fields("改档货主");
        String acceptNo = data(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("edit-create"), createBody(initial))).get("acceptNo").toString();
        registerAcceptNo(acceptNo);
        Map<String, Object> created = resultOf(awaitAcceptance(acceptNo, token, "SUCCEEDED"));
        long ownerId = Long.parseLong(created.get("ownerId").toString());
        registerOwnerRow(ownerId);
        String ownerNo = created.get("ownerNo").toString();

        OwnerFields changed = new OwnerFields("改档货主（新名称）", "李四", "021-62345679",
            initial.creditCode());
        String editKey = newKey("edit");
        ResponseEntity<Map<String, Object>> edited = call(HttpMethod.PUT,
            "/api/hub/owner-archives/" + ownerNo, token, editKey,
            editBody(changed, 0L));
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(data(edited).get("status")).as("受理时快照为当前状态").isEqualTo("ACTIVE");
        String editAcceptNo = data(edited).get("acceptNo").toString();
        registerAcceptNo(editAcceptNo);

        Map<String, Object> editResult = resultOf(awaitAcceptance(editAcceptNo, token, "SUCCEEDED"));
        assertThat(editResult.get("ownerNo")).as("编号不随改档变化").isEqualTo(ownerNo);
        assertThat(editResult.get("version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select owner_name from wms_owner where owner_no = ?",
            String.class, ownerNo)).isEqualTo("改档货主（新名称）");

        // 版本落后：用旧版本 0 再改一次，受理结果给可重试的版本冲突
        String staleKey = newKey("edit-stale");
        String staleAcceptNo = data(call(HttpMethod.PUT, "/api/hub/owner-archives/" + ownerNo,
            token, staleKey, editBody(fields("改档货主（过期版本）"), 0L)))
            .get("acceptNo").toString();
        registerAcceptNo(staleAcceptNo);
        Map<String, Object> failed = awaitAcceptance(staleAcceptNo, token, "FAILED");
        assertThat(failed.get("errorCode")).isEqualTo(OwnerErrorCodes.CONFLICT_VERSION);
        assertThat(failed.get("errorMessage")).isNotNull();
        assertThat(jdbc.queryForObject("select version from wms_owner where owner_no = ?",
            Long.class, ownerNo)).as("版本只前进一次").isEqualTo(1L);
    }

    @Test
    @DisplayName("停用：留痕含原因与操作人；启用后状态回可用、编号不变；重复停用 STATE_INVALID")
    void 停用与启用() {
        long accountId = createHubAdminAccount();
        String token = hubToken(accountId);
        OwnerFields fields = fields("停用启用货主");
        String ownerNo = buildOwner(token, fields);

        String disableAcceptNo = data(call(HttpMethod.POST,
            "/api/hub/owner-archives/" + ownerNo + "/disable", token, newKey("disable"),
            reasonBody("合同到期", 0L))).get("acceptNo").toString();
        registerAcceptNo(disableAcceptNo);
        Map<String, Object> disabled = resultOf(awaitAcceptance(disableAcceptNo, token, "SUCCEEDED"));
        assertThat(disabled.get("status")).isEqualTo("DISABLED");
        assertThat(disabled.get("ownerNo")).isEqualTo(ownerNo);

        Map<String, Object> detail = data(call(HttpMethod.GET,
            "/api/hub/owner-archives/" + ownerNo, token, null, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> lastChange = (Map<String, Object>) detail.get("lastStatusChange");
        assertThat(lastChange.get("action")).isEqualTo("DISABLE");
        assertThat(lastChange.get("reason")).isEqualTo("合同到期");
        assertThat(lastChange.get("operator")).isEqualTo(accountNameOf(accountId));

        // 重复停用：受理结果 STATE_INVALID
        String repeatAcceptNo = data(call(HttpMethod.POST,
            "/api/hub/owner-archives/" + ownerNo + "/disable", token, newKey("disable-again"),
            reasonBody("重复停用", 1L))).get("acceptNo").toString();
        registerAcceptNo(repeatAcceptNo);
        assertThat(awaitAcceptance(repeatAcceptNo, token, "FAILED").get("errorCode"))
            .isEqualTo(OwnerErrorCodes.STATE_INVALID);

        // 启用：受理时快照为停用态
        ResponseEntity<Map<String, Object>> enabled = call(HttpMethod.POST,
            "/api/hub/owner-archives/" + ownerNo + "/enable", token, newKey("enable"),
            reasonBody("合同续签", 1L));
        assertThat(data(enabled).get("status")).as("受理时快照为停用态").isEqualTo("DISABLED");
        String enableAcceptNo = data(enabled).get("acceptNo").toString();
        registerAcceptNo(enableAcceptNo);
        Map<String, Object> active = resultOf(awaitAcceptance(enableAcceptNo, token, "SUCCEEDED"));
        assertThat(active.get("status")).isEqualTo("ACTIVE");
        assertThat(active.get("ownerNo")).isEqualTo(ownerNo);
        assertThat(active.get("version")).isEqualTo(2);

        // 对可用再启用：STATE_INVALID
        String againAcceptNo = data(call(HttpMethod.POST,
            "/api/hub/owner-archives/" + ownerNo + "/enable", token, newKey("enable-again"),
            reasonBody("重复启用", 2L))).get("acceptNo").toString();
        registerAcceptNo(againAcceptNo);
        assertThat(awaitAcceptance(againAcceptNo, token, "FAILED").get("errorCode"))
            .isEqualTo(OwnerErrorCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("停用态改档在受理结果返回 STATE_INVALID，档案内容不变")
    void 停用态改档() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("停用态改档货主");
        String ownerNo = buildOwner(token, fields);
        String disableAcceptNo = data(call(HttpMethod.POST,
            "/api/hub/owner-archives/" + ownerNo + "/disable", token, newKey("disable-before-edit"),
            reasonBody("先停用", 0L))).get("acceptNo").toString();
        registerAcceptNo(disableAcceptNo);
        awaitAcceptance(disableAcceptNo, token, "SUCCEEDED");

        String editAcceptNo = data(call(HttpMethod.PUT, "/api/hub/owner-archives/" + ownerNo,
            token, newKey("edit-disabled"), editBody(fields, 1L))).get("acceptNo").toString();
        registerAcceptNo(editAcceptNo);

        assertThat(awaitAcceptance(editAcceptNo, token, "FAILED").get("errorCode"))
            .isEqualTo(OwnerErrorCodes.STATE_INVALID);
        assertThat(jdbc.queryForObject("select owner_name from wms_owner where owner_no = ?",
            String.class, ownerNo)).isEqualTo(fields.ownerName());
    }

    @Test
    @DisplayName("信用代码重复在受理结果返回 RULE_DUPLICATE_CREDIT_CODE，不产生第二条档案")
    void 信用代码重复() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields first = fields("信用代码货主 A");
        buildOwner(token, first);

        String acceptNo = data(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("dup-credit"), createBody(new OwnerFields("信用代码货主 B", "王五",
                "021-62345670", first.creditCode())))).get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        assertThat(awaitAcceptance(acceptNo, token, "FAILED").get("errorCode"))
            .isEqualTo(OwnerErrorCodes.RULE_DUPLICATE_CREDIT_CODE);
        assertThat(jdbc.queryForObject("select count(*) from wms_owner where credit_code = ?",
            Integer.class, first.creditCode())).isEqualTo(1);
    }

    @Test
    @DisplayName("同名不阻断：名称相同、信用代码不同，两次建档都成功，靠货主编号区分")
    void 同名不阻断() {
        String token = hubToken(createHubAdminAccount());
        String ownerName = "同名货主-" + nextId();

        String firstNo = buildOwner(token, fields(ownerName));
        String secondNo = buildOwner(token, fields(ownerName));

        assertThat(firstNo).as("同名只靠系统编号区分").isNotEqualTo(secondNo);

        Map<String, Object> page = data(call(HttpMethod.GET,
            "/api/hub/owner-archives?ownerName=" + ownerName, token, null, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).extracting(item -> item.get("ownerNo"))
            .containsExactlyInAnyOrder(firstNo, secondNo);
        assertThat(items).extracting(item -> item.get("ownerName")).containsOnly(ownerName);
    }

    @Test
    @DisplayName("四项必填逐项缺失：400 PARAM_INVALID 且 details[].field 命中该字段")
    void 四项必填() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields complete = fields("必填校验货主");

        assertFieldError(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("missing-name"),
            createBody(new OwnerFields(null, complete.contactName(), complete.contactPhone(),
                complete.creditCode()))), "ownerName");
        assertFieldError(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("missing-contact"),
            createBody(new OwnerFields(complete.ownerName(), "  ", complete.contactPhone(),
                complete.creditCode()))), "contactName");
        assertFieldError(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("missing-phone"),
            createBody(new OwnerFields(complete.ownerName(), complete.contactName(), null,
                complete.creditCode()))), "contactPhone");
        assertFieldError(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("missing-credit"),
            createBody(new OwnerFields(complete.ownerName(), complete.contactName(),
                complete.contactPhone(), ""))), "creditCode");
    }

    @Test
    @DisplayName("未知字段与系统生成字段：400 PARAM_INVALID，details[].field 指出字段名")
    void 未知字段() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("未知字段货主");

        assertFieldError(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("unknown"),
            "{" + createBody(fields).substring(1, createBody(fields).length() - 1)
                + ",\"locationId\":\"L-01\"}"), "locationId");
        // ownerNo 由系统生成、不可变：请求体里出现即未知字段（契约 API-023「请求」）
        assertFieldError(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("owner-no"),
            "{" + createBody(fields).substring(1, createBody(fields).length() - 1)
                + ",\"ownerNo\":\"OW000001\"}"), "ownerNo");
    }

    @Test
    @DisplayName("请求体超 16 KB：入口 400 PARAM_INVALID、details[].field=body，不解析也不受理")
    void 请求体超限() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("超限货主");
        // 合法 JSON，只是体积超过 16 KB：判定必须发生在解析之前，所以这里命中的是 body 而不是某个字段。
        String oversized = "{\"ownerName\":\"" + "长".repeat(6000)
            + "\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\",\"creditCode\":\""
            + fields.creditCode() + "\"}";
        assertThat(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .as("构造的载荷必须真的超过上限，否则本用例测不到拦截").isGreaterThan(16 * 1024);

        assertFieldError(sendOversized("/api/hub/owner-archives", token, oversized), "body");
    }

    @Test
    @DisplayName("停用与启用缺原因或版本号：400 PARAM_INVALID 且给出字段名")
    void 停用启用必填() {
        String token = hubToken(createHubAdminAccount());
        String ownerNo = buildOwner(token, fields("必填参数货主"));

        assertFieldError(call(HttpMethod.POST,
            "/api/hub/owner-archives/" + ownerNo + "/disable", token, newKey("no-reason"),
            "{\"version\":0}"), "reason");
        assertFieldError(call(HttpMethod.POST,
            "/api/hub/owner-archives/" + ownerNo + "/disable", token, newKey("no-version"),
            "{\"reason\":\"合同到期\"}"), "version");
        assertFieldError(call(HttpMethod.POST,
            "/api/hub/owner-archives/" + ownerNo + "/disable", token, newKey("neg-version"),
            "{\"reason\":\"合同到期\",\"version\":-1}"), "version");
    }

    @Test
    @DisplayName("受理号不存在返回 404 NOT_FOUND_OWNER；商品档案的受理号在 API-027 查不到")
    void 受理号隔离() {
        String token = hubToken(createHubAdminAccount());

        ResponseEntity<Map<String, Object>> missing = call(HttpMethod.GET,
            "/api/hub/owner-archives/acceptances/ARC000000000000000000", token, null, null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code")).isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);

        String skuAcceptNo = insertProcessingAcceptance("API-006", null, Duration.ofSeconds(1));
        ResponseEntity<Map<String, Object>> crossBusiness = call(HttpMethod.GET,
            "/api/hub/owner-archives/acceptances/" + skuAcceptNo, token, null, null);
        assertThat(crossBusiness.getStatusCode())
            .as("商品档案的受理单不通过货主档案的受理查询暴露（契约 1.6）")
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(crossBusiness.getBody().get("code")).isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);
    }

    @Test
    @DisplayName("处理中超过六十秒：503 DEPENDENCY_UNAVAILABLE，可重试并带重试间隔")
    void 受理超时兜底() {
        String token = hubToken(createHubAdminAccount());
        String acceptNo = insertProcessingAcceptance("API-023", null, Duration.ofSeconds(61));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/api/hub/owner-archives/acceptances/" + acceptNo, token, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code"))
            .isEqualTo(OwnerErrorCodes.DEPENDENCY_UNAVAILABLE);
        assertThat(response.getBody().get("retryable")).isEqualTo(Boolean.TRUE);
        assertThat(response.getBody().get("retryAfterMs")).isInstanceOf(Number.class);
        assertThat(response.getBody()).as("降级不得返回空数据冒充成功").doesNotContainKey("data");
    }

    @Test
    @DisplayName("缺少 Idempotency-Key：400 PARAM_INVALID，不产生受理单与档案")
    void 缺幂等键() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("缺幂等键货主");
        long before = jdbc.queryForObject("select count(*) from wms_owner", Long.class);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, null, createBody(fields));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(OwnerErrorCodes.PARAM_INVALID);
        assertThat(response.getBody().get("traceId")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from wms_owner", Long.class))
            .isEqualTo(before);
    }

    @Test
    @DisplayName("请求体含系统生成字段改档、或版本号为字符串：400 PARAM_INVALID")
    void 改档请求体边界() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("改档边界货主");
        String ownerNo = buildOwner(token, fields);

        assertFieldError(call(HttpMethod.PUT, "/api/hub/owner-archives/" + ownerNo, token,
            newKey("edit-owner-no"),
            "{\"ownerName\":\"新名字\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\","
                + "\"creditCode\":\"" + fields.creditCode() + "\",\"version\":0,"
                + "\"status\":\"DISABLED\"}"), "status");
        ResponseEntity<Map<String, Object>> badVersion = call(HttpMethod.PUT,
            "/api/hub/owner-archives/" + ownerNo, token, newKey("edit-string-version"),
            "{\"ownerName\":\"新名字\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\","
                + "\"creditCode\":\"" + fields.creditCode() + "\",\"version\":\"0\"}");
        assertThat(badVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badVersion.getBody().get("code")).isEqualTo(OwnerErrorCodes.PARAM_INVALID);
    }

    /** 建档并返回货主编号，登记清理。 */
    private String buildOwner(String token, OwnerFields fields) {
        String acceptNo = data(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("build"), createBody(fields))).get("acceptNo").toString();
        registerAcceptNo(acceptNo);
        Map<String, Object> result = resultOf(awaitAcceptance(acceptNo, token, "SUCCEEDED"));
        registerOwnerRow(Long.parseLong(result.get("ownerId").toString()));
        return result.get("ownerNo").toString();
    }

    /**
     * 发送一个超大请求体：用 JDK 的 HttpClient 而不是 TestRestTemplate。
     *
     * <p>体积拦截读的是 {@code Content-Length}，而 {@code TestRestTemplate} 底层在某些情况下会用
     * 分块传输（没有长度头），那样测的就不是「入口拒绝」，而是「解析后拒绝」。JDK 客户端对
     * {@code BodyPublishers.ofString} 一定会带长度头，与真实调用方一致。
     */
    private ResponseEntity<Map<String, Object>> sendOversized(String path, String token, String json) {
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .header("Idempotency-Key", newKey("oversized"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json,
                    java.nio.charset.StandardCharsets.UTF_8))
                .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(response.body(), Map.class);
            return ResponseEntity.status(response.statusCode()).body(body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 断言 400 + PARAM_INVALID + details 里命中给定字段。 */    private void assertFieldError(ResponseEntity<Map<String, Object>> response, String field) {
        assertThat(response.getStatusCode())
            .as("期望 400，实际 %s，响应体 %s", response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(OwnerErrorCodes.PARAM_INVALID);
        assertThat(response.getBody().get("retryable")).isEqualTo(Boolean.FALSE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details =
            (List<Map<String, Object>>) response.getBody().get("details");
        assertThat(details).as("字段级错误必须带 details").isNotNull();
        assertThat(details).extracting(detail -> detail.get("field")).contains(field);
        assertThat(details).extracting(detail -> detail.get("reason"))
            .allSatisfy(reason -> assertThat(reason).isNotNull());
    }

    /**
     * 请求体：{@code null} 必须写成 JSON 的 {@code null}，不能拼成字符串 {@code "null"}。
     *
     * <p>拼错会让「字段缺失」变成「字段值是四个字母」，必填校验自然放行——
     * 这类假绿比失败更难发现。
     */
    private String createBody(OwnerFields fields) {
        return "{\"ownerName\":" + json(fields.ownerName())
            + ",\"contactName\":" + json(fields.contactName())
            + ",\"contactPhone\":" + json(fields.contactPhone())
            + ",\"creditCode\":" + json(fields.creditCode()) + "}";
    }

    private String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String editBody(OwnerFields fields, Long version) {
        return "{" + createBody(fields).substring(1, createBody(fields).length() - 1)
            + ",\"version\":" + version + "}";
    }

    private String reasonBody(String reason, Long version) {
        return "{\"reason\":\"" + reason + "\",\"version\":" + version + "}";
    }

    private String newKey(String scene) {
        String key = "it-owner-" + scene + "-" + nextId();
        registerIdempotencyKey(key);
        return key;
    }

    private String accountNameOf(long accountId) {
        return jdbc.queryForObject("select account_name from wms_account where id = ?",
            String.class, accountId);
    }
}
