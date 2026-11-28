package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流与降级（tasks 1.12 的验收口径、契约 1.1「限流与熔断」、1.2「降级」）。
 *
 * <p>用 {@code wms.archive.rate-limit-per-minute=3} 起一个独立上下文：真打满阈值再看行为，
 * 不靠 mock 计数。三条断言各管一件事：
 * <ul>
 *   <li>超限返回 429 {@code RATE_LIMITED}，带 {@code Retry-After} 与 {@code retryAfterMs}，
 *       且 {@code retryable=true}；</li>
 *   <li>打满查询接口不影响写接口——池按「端 + 接口」分开（D18）；</li>
 *   <li>依赖不可用（受理仍处理中超过轮询上限）返回 503 而不是 200 空数据。</li>
 * </ul>
 *
 * <p>Redis 里的计数键在用例前后各清一次：限流窗口是六十秒，不清会把上一次的计数带进来，
 * 也会把本类的低阈值带给同一分钟内的其他用例。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "wms.archive.rate-limit-per-minute=3")
class OwnerArchiveRateLimitIT extends OwnerApiTestSupport {

    private static final String POOL_PREFIX = "hub:archive:rate:hub:";

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void clearRateLimitPools() {
        for (String apiCode : List.of("API-020", "API-021", "API-022", "API-023", "API-024",
            "API-025", "API-026", "API-027")) {
            redis.delete(POOL_PREFIX + apiCode);
        }
    }

    @Test
    @DisplayName("查询接口超限：429 RATE_LIMITED，带 Retry-After 与 retryAfterMs")
    void 查询限流() {
        String token = hubToken(createHubAdminAccount());
        for (int index = 0; index < 3; index++) {
            assertThat(call(HttpMethod.GET, "/api/hub/owner-archives", token, null, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map<String, Object>> limited = call(HttpMethod.GET,
            "/api/hub/owner-archives", token, null, null);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code")).isEqualTo(OwnerErrorCodes.RATE_LIMITED);
        assertThat(limited.getBody().get("retryable")).isEqualTo(Boolean.TRUE);
        assertThat(limited.getBody().get("retryAfterMs")).isInstanceOf(Number.class);
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
            .as("429 必须带 Retry-After").isNotNull();
    }

    @Test
    @DisplayName("打满查询接口不影响写接口：池按端加接口分开（D18）")
    void 查询打满不影响写入() {
        String token = hubToken(createHubAdminAccount());
        for (int index = 0; index < 3; index++) {
            call(HttpMethod.GET, "/api/hub/owner-archives", token, null, null);
        }
        assertThat(call(HttpMethod.GET, "/api/hub/owner-archives", token, null, null)
            .getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        OwnerFields fields = fields("限流隔离货主");
        String key = newKey("rate-isolation");
        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, key, "{\"ownerName\":\"" + fields.ownerName()
                + "\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\",\"creditCode\":\""
                + fields.creditCode() + "\"}");

        assertThat(created.getStatusCode())
            .as("查询打满不得影响写接口，响应体 %s", created.getBody())
            .isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = data(created).get("acceptNo").toString();
        registerAcceptNo(acceptNo);
        registerOwnerRow(Long.parseLong(
            resultOf(awaitAcceptance(acceptNo, token, "SUCCEEDED")).get("ownerId").toString()));
    }

    @Test
    @DisplayName("依赖不可用：受理超时返回 503，不返回 200 空数据冒充成功")
    void 依赖不可用不返回空数据() {
        String token = hubToken(createHubAdminAccount());
        String acceptNo = insertProcessingAcceptance("API-023", null, java.time.Duration.ofSeconds(61));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/api/hub/owner-archives/acceptances/" + acceptNo, token, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code"))
            .isEqualTo(OwnerErrorCodes.DEPENDENCY_UNAVAILABLE);
        assertThat(response.getBody().get("retryable")).isEqualTo(Boolean.TRUE);
        assertThat(response.getBody()).doesNotContainKey("data");
    }

    private String newKey(String scene) {
        String key = "it-owner-rate-" + scene + "-" + nextId();
        registerIdempotencyKey(key);
        return key;
    }
}
