package com.huoling.wms.site.api;

import com.huoling.wms.common.archive.ArchivePermissionCodes;
import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.support.SiteDbTestSupport;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单仓端接口契约（契约 API-010、API-011、API-014、API-017 与「单仓端会话与权限」）：
 * 令牌必须带端标识 {@code SITE} 与唯一 {@code siteId}，多仓声明与非本仓一律 {@code FORBIDDEN_SITE}；
 * 写接口返回 202 与受理号，结果由受理查询给出，摘要里的申请编号取契约字段名 {@code requestId}；
 * 入口同步校验的问题（变更字段闭集外）直接 400 {@code PARAM_INVALID}，不落受理记录。
 *
 * <p>走真实 HTTP：令牌过滤器、权限副本校验、异常映射与 JSON 序列化都在链路上，不 mock 任何一层。
 */
@SpringBootTest(classes = com.huoling.wms.site.SiteApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"wms.site.id=1001"})
class SiteArchiveApiIT extends SiteDbTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SiteProperties properties;

    @Test
    @DisplayName("API-010 本仓查询返回 200，逐字段使用契约英文名并带数据时间戳")
    void 本仓查询契约() {
        String runTag = "api-" + nextId();
        applySnapshot("T-A-1", runTag + "-矿泉水", "ACTIVE", 3L, Instant.now());

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/api/site/archives?name=" + runTag, siteToken("1001", List.of(ArchivePermissionCodes.SITE_READ)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(data).containsKeys("items", "nextCursor", "hasMore", "dataTimestamp");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsKeys("archiveId", "ownerId", "ownerCode", "name", "barcode",
            "baseUnit", "storageCondition", "spec", "status", "version", "dataTimestamp", "stale");
    }

    @Test
    @DisplayName("多仓声明的令牌被拒，不返回任何档案数据")
    void 多仓声明被拒() {
        String token = siteToken(List.of("1001", "1002"), List.of(ArchivePermissionCodes.SITE_READ));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, "/api/site/archives", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
        assertThat(response.getBody().get("data")).isNull();
    }

    @Test
    @DisplayName("缺 siteId 声明的令牌被拒")
    void 缺仓标识被拒() {
        String token = Jwts.builder().subject("7").id("jti-" + nextId())
            .claim("client", "SITE").claim("permissions", List.of(ArchivePermissionCodes.SITE_READ))
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)))
            .signWith(key()).compact();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, "/api/site/archives",
            "Bearer " + token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("非本仓令牌被拒，不能跨仓读数据")
    void 跨仓令牌被拒() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, "/api/site/archives",
            siteToken("2002", List.of(ArchivePermissionCodes.SITE_READ)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("端标识不是 SITE 的令牌被拒")
    void 端标识不符被拒() {
        String hubToken = "Bearer " + Jwts.builder().subject("7").id("jti-" + nextId())
            .claim("client", "HUB").claim("siteId", "1001")
            .claim("permissions", List.of(ArchivePermissionCodes.SITE_READ))
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)))
            .signWith(key()).compact();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, "/api/site/archives", hubToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("权限副本缺 archive.site.read 时被拒")
    void 缺功能权限被拒() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, "/api/site/archives",
            siteToken("1001", List.of(ArchivePermissionCodes.SITE_CHANGE)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveContractCodes.FORBIDDEN_SITE);
    }

    @Test
    @DisplayName("API-011 首次返回 202 与受理号，同键重复回放同一受理号，结果由 API-017 给出")
    void 发起变更申请契约() {
        String runTag = "api-create-" + nextId();
        String archiveNo = applySnapshot("T-A-2", runTag, "ACTIVE", 4L, Instant.now());
        String token = siteToken("1001",
            List.of(ArchivePermissionCodes.SITE_READ, ArchivePermissionCodes.SITE_CHANGE));
        String key = "site-create-" + nextId();
        String body = "{\"archiveId\":\"" + archiveNo + "\",\"changeField\":\"name\","
            + "\"targetValue\":\"新名称\",\"reason\":\"换包装\"}";

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
            "/api/site/archive-change-requests", token, key, body);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> accepted = dataOf(created);
        assertThat(accepted.get("acceptNo")).as("受理号是字符串标识").isInstanceOf(String.class);
        assertThat(accepted.get("acceptNo").toString()).isNotBlank();
        assertThat(accepted.get("status")).as("变更申请受理时状态快照为待审核").isEqualTo("PENDING");
        String acceptNo = accepted.get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        // 同键同体重复提交：回放首次受理号，不产生第二条受理记录
        ResponseEntity<Map<String, Object>> replay = call(HttpMethod.POST,
            "/api/site/archive-change-requests", token, key, body);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(dataOf(replay).get("acceptNo")).isEqualTo(acceptNo);

        ResponseEntity<Map<String, Object>> acceptance = call(HttpMethod.GET,
            "/api/site/archive-acceptances/" + acceptNo, token);
        assertThat(acceptance.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> outcome = dataOf(acceptance);
        assertThat(outcome.get("acceptStatus")).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) outcome.get("result");
        // 契约「字段结构与枚举」：受理结果摘要里的申请编号叫 requestId，不是内部命名的 requestNo
        assertThat(result).as("受理结果摘要含申请编号与状态").containsKeys("requestId", "status");
        assertThat(result.get("requestId")).as("申请编号是字符串标识").isInstanceOf(String.class);
        assertThat(result.get("requestId").toString()).isNotBlank();
        assertThat(result.get("status")).isEqualTo("PENDING");
        registerRequestNo(result.get("requestId").toString());

        // API-014 本仓申请查询能看到这条申请
        ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET,
            "/api/site/archive-change-requests?archiveId=" + archiveNo, token);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) dataOf(list).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsKeys("requestNo", "changeField", "targetValue", "status",
            "reviewReason");
    }

    @Test
    @DisplayName("API-014 按申请字段排序并键集翻页，不重不漏")
    void 变更申请列表排序与翻页契约() {
        String runTag = "api-page-" + nextId();
        String[] fields = {"spec", "category", "name"};
        List<String> created = new ArrayList<>(fields.length);
        for (String field : fields) {
            String archiveNo = applySnapshot("T-A-" + field, runTag + "-" + field, "ACTIVE", 6L,
                Instant.now());
            String acceptNo = "SAC-" + nextId();
            // 受理号先登记再落库：申请行一进库就出现在列表里，之后任何断言失败都不能把它留成残留
            registerAcceptNo(acceptNo);
            String requestNo = changeRequestService.create(SITE_ID, archiveNo, field,
                runTag + "-值", "排序用例", acceptNo).requestNo();
            registerRequestNo(requestNo);
            created.add(requestNo);
        }
        String token = siteToken("1001", List.of(ArchivePermissionCodes.SITE_READ));

        // API-014 只按站点过滤，不认运行时标记：同库上历史运行遗留的申请行无法用请求参数排除，
        // 断言因此不能建立在「表里只有本次这三条」上。改为先取一次库内快照，再用
        // 「翻页结果覆盖快照 + 全局升序 + 申请编号不重复」验键集翻页不重不漏。
        Set<String> existing = new LinkedHashSet<>(jdbc.queryForList(
            "select request_no from site_archive_change_request where site_id = ?",
            String.class, SITE_ID));

        int limit = 2;
        List<String> pagedFields = new ArrayList<>();
        List<String> pagedRequestNos = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        boolean hasMore;
        do {
            String path = "/api/site/archive-change-requests?sortBy=name&sortDir=asc&limit=" + limit
                + (cursor == null ? "" : "&cursor=" + cursor);
            Map<String, Object> page = dataOf(call(HttpMethod.GET, path, token));
            List<Map<String, Object>> items = itemsOf(page);
            pages++;

            assertThat(items).as("第 %d 页不超过 limit", pages).hasSizeLessThanOrEqualTo(limit);
            assertThat(changeFields(items)).as("第 %d 页按申请字段升序", pages).isSorted();
            if (pages == 1) {
                // 库内至少已有本次三条申请：首页必按 limit 取满，且不是最后一页
                assertThat(items).as("首页按 limit 取满").hasSize(limit);
                assertThat(page.get("hasMore")).as("首页之后还有下一页").isEqualTo(Boolean.TRUE);
            }
            hasMore = Boolean.TRUE.equals(page.get("hasMore"));
            Object nextCursor = page.get("nextCursor");
            if (hasMore) {
                assertThat(nextCursor).as("还有下一页时下一页锚点必填").isNotNull();
                assertThat(nextCursor.toString()).isNotBlank();
                cursor = nextCursor.toString();
            } else {
                assertThat(nextCursor).as("末页不返回下一页锚点").isNull();
            }
            items.forEach(item -> {
                pagedFields.add(item.get("changeField").toString());
                pagedRequestNos.add(item.get("requestNo").toString());
            });
            assertThat(pages).as("翻页超过 50 页仍未结束，游标可能无法前进").isLessThan(50);
        } while (hasMore);

        // 键集翻页至少要翻两页才算验到；合并结果全局升序，库内已有申请一条不漏、同一申请不重复
        assertThat(pages).as("limit=2 且库内至少三条申请，至少要翻两页").isGreaterThanOrEqualTo(2);
        assertThat(pagedFields).as("合并结果全局按申请字段升序").isSorted();
        assertThat(pagedRequestNos).as("同一申请不得跨页重复出现").doesNotHaveDuplicates();
        assertThat(pagedRequestNos).as("翻页不得漏掉库内已有申请").containsAll(existing);
        for (String requestNo : created) {
            assertThat(pagedRequestNos.stream().filter(requestNo::equals).count())
                .as("本次申请 %s 在合并结果里恰好出现一次", requestNo).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("非本仓可见档案发起变更申请：受理后结果失败并带 NOT_FOUND_ARCHIVE")
    void 非本仓档案发起申请() {
        String token = siteToken("1001",
            List.of(ArchivePermissionCodes.SITE_READ, ArchivePermissionCodes.SITE_CHANGE));
        String key = "site-foreign-" + nextId();
        String body = "{\"archiveId\":\"SKU-NOT-IN-THIS-SITE\",\"changeField\":\"name\","
            + "\"targetValue\":\"新名称\",\"reason\":\"换包装\"}";

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
            "/api/site/archive-change-requests", token, key, body);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = dataOf(created).get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        Map<String, Object> outcome = dataOf(call(HttpMethod.GET,
            "/api/site/archive-acceptances/" + acceptNo, token));
        assertThat(outcome.get("acceptStatus")).isEqualTo("FAILED");
        assertThat(outcome.get("errorCode")).isEqualTo(ArchiveContractCodes.NOT_FOUND_ARCHIVE);
    }

    @Test
    @DisplayName("变更字段闭集外的 baseUnit 在入口同步 400 PARAM_INVALID，不落受理记录也不落半成品申请")
    void 变更字段闭集() {
        String runTag = "api-field-" + nextId();
        String archiveNo = applySnapshot("T-A-3", runTag, "ACTIVE", 5L, Instant.now());
        String token = siteToken("1001",
            List.of(ArchivePermissionCodes.SITE_READ, ArchivePermissionCodes.SITE_CHANGE));
        String key = "site-field-" + nextId();
        String body = "{\"archiveId\":\"" + archiveNo + "\",\"changeField\":\"baseUnit\","
            + "\"targetValue\":\"箱\",\"reason\":\"改单位\"}";

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
            "/api/site/archive-change-requests", token, key, body);

        // 闭集校验在入口同步完成（契约「鲁棒性约定」）：不进异步受理，直接 400，响应不带受理结果
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(created.getBody().get("code")).isEqualTo(ArchiveContractCodes.PARAM_INVALID);
        assertThat(created.getBody().get("data")).as("参数错误不返回受理结果").isNull();
        assertThat(jdbc.queryForObject(
            "select count(*) from site_archive_acceptance where idempotency_key = ?",
            Integer.class, key)).as("入口拒绝不落受理记录").isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from site_archive_change_request where archive_id = ?",
            Integer.class, archiveNo)).isZero();
    }

    @Test
    @DisplayName("缺少 Idempotency-Key 返回 400 PARAM_INVALID")
    void 缺幂等键契约() {
        String token = siteToken("1001",
            List.of(ArchivePermissionCodes.SITE_READ, ArchivePermissionCodes.SITE_CHANGE));
        String body = "{\"archiveId\":\"SKU-X\",\"changeField\":\"name\","
            + "\"targetValue\":\"新名称\",\"reason\":\"换包装\"}";

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST,
            "/api/site/archive-change-requests", token, null, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveContractCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("受理号不存在按目标不存在处理")
    void 受理号不存在契约() {
        String token = siteToken("1001", List.of(ArchivePermissionCodes.SITE_READ));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/api/site/archive-acceptances/ARC000000000000000000", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo(ArchiveContractCodes.NOT_FOUND_ARCHIVE);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private List<String> changeFields(List<Map<String, Object>> items) {
        return items.stream().map(item -> item.get("changeField").toString()).toList();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    private String siteToken(String siteId, List<String> permissions) {
        return "Bearer " + token(siteId, permissions);
    }

    private String siteToken(List<String> siteIds, List<String> permissions) {
        return "Bearer " + token(siteIds, permissions);
    }

    private String token(String siteId, List<String> permissions) {
        return token((Object) siteId, permissions);
    }

    private String token(List<String> siteIds, List<String> permissions) {
        return token((Object) siteIds, permissions);
    }

    private String token(Object siteIdClaim, List<String> permissions) {
        return Jwts.builder()
            .subject("7")
            .id("jti-" + nextId())
            .claim("client", "SITE")
            .claim("siteId", siteIdClaim)
            .claim("permissions", permissions)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)))
            .signWith(key())
            .compact();
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token) {
        return call(method, path, token, null, null);
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
        return restTemplate.exchange("http://localhost:" + port + path, method,
            new HttpEntity<>(json, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {
            });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
            .as("期望 2xx，实际 %s，响应体 %s", response.getStatusCode(), response.getBody()).isTrue();
        Map<String, Object> payload = response.getBody();
        assertThat(payload).isNotNull();
        return (Map<String, Object>) payload.get("data");
    }
}
