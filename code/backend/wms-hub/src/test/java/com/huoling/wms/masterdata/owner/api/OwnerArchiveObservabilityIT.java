package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.owner.application.OwnerAcceptanceBacklog;
import com.huoling.wms.masterdata.owner.application.OwnerMetrics;
import com.huoling.wms.masterdata.owner.application.OwnerStatusDeliveryMetrics;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可观测落点（tasks 1.18、design「可观测与兼容回滚」）。
 *
 * <p>验证方式就是验收标准原文：「指标可在本地端点观察」。这里打真端点
 * （{@code /actuator/metrics/{name}}），而不是断言代码里有没有调过计数方法——
 * 指标名写错、标签漏配、端点没暴露，都只有读端点才能发现。
 *
 * <p>六项指标各一条：受理与写耗时的计数、数据域拒绝的计数、受理积压的仪表、
 * 状态下行延迟与死信深度（由真实的下行观测实现驱动），以及闸门侧的两项在单仓端的用例里。
 *
 * <p>下行两项刻意不通过 RabbitMQ 触发：本地集成环境没有真实中间件，`publish` 会失败，
 * 拿不到「投递成功后」的那一刻。这里调真实的 {@code OwnerStatusDeliveryMetrics}（Spring 装配的那个），
 * 它正是投递器回调的实现——证明「指标名、标签与注册时机」这三件端点能观察到的事实，
 * 「投递器在什么时机调它」由 {@code ArchiveOutboxDispatcherTest} 用替身验证。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerArchiveObservabilityIT extends OwnerApiTestSupport {

    @Autowired
    private OwnerAcceptanceBacklog backlog;

    @Autowired
    private OwnerStatusDeliveryMetrics delivery;

    @Test
    @DisplayName("受理计数与写耗时：按接口与结果打标签，可在端点观察")
    void 受理指标可观察() {
        String token = hubToken(createHubAdminAccount());
        OwnerFields fields = fields("指标货主");
        String acceptNo = data(call(HttpMethod.POST, "/api/hub/owner-archives", token,
            newKey("metrics"), "{\"ownerName\":\"" + fields.ownerName()
                + "\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\",\"creditCode\":\""
                + fields.creditCode() + "\"}")).get("acceptNo").toString();
        registerAcceptNo(acceptNo);
        registerOwnerRow(Long.parseLong(
            resultOf(awaitAcceptance(acceptNo, token, "SUCCEEDED")).get("ownerId").toString()));

        Map<String, Object> acceptCounter = metric("owner_archive_accept_total");
        assertThat(measurement(acceptCounter)).as("受理计数必须已累计").isPositive();
        assertThat(tags(acceptCounter)).contains("api", "result");

        Map<String, Object> duration = metric("owner_archive_write_duration");
        assertThat(measurement(duration)).as("写耗时必须有观测值").isPositive();
        assertThat(tags(duration)).contains("api");
    }

    @Test
    @DisplayName("数据域拒绝计数：非全域账号建档被拒即出现在端点，带 reason 标签")
    void 越权指标可观察() {
        var inside = prepareOwner("指标域内货主-" + nextId(), uniqueCreditCode());
        String token = hubToken(createScopedHubAccount(List.of(Long.parseLong(inside.ownerId()))));

        ResponseEntity<Map<String, Object>> forbidden = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, newKey("metrics-denied"),
            "{\"ownerName\":\"指标越权建档\",\"contactName\":\"张三\","
                + "\"contactPhone\":\"021-62345678\",\"creditCode\":\"" + uniqueCreditCode() + "\"}");
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> denied = metric("owner_scope_denied_total");
        assertThat(measurement(denied)).isPositive();
        assertThat(tags(denied)).contains("reason");
    }

    @Test
    @DisplayName("受理积压仪表：刷新后可读到处理中条数")
    void 受理积压可观察() {
        backlog.refresh();

        Map<String, Object> gauge = metric("owner_archive_accept_processing");
        assertThat(measurement(gauge)).isGreaterThanOrEqualTo(0d);
    }

    @Test
    @DisplayName("状态下行延迟：投递成功后可在端点观察，且按 now - changedAt 计")
    void 下行延迟可观察() {
        delivery.delivered("OW000001#7", Instant.now().minusSeconds(2));

        Map<String, Object> lag = metric("owner_status_downlink_lag_seconds");
        assertThat(measurement(lag))
            .as("延迟取 now - changedAt，两秒前的事件读数应不小于 1 秒")
            .isGreaterThanOrEqualTo(1d);
    }

    @Test
    @DisplayName("死信深度：转发死信后可在端点观察（累计条数）")
    void 死信深度可观察() {
        // 首次转发才注册指标：先转发一条把指标建出来，再读基线。
        delivery.deadLettered("OW000002#7");
        double before = measurement(metric("owner_status_dlq_depth"));
        delivery.deadLettered("OW000002#8");

        assertThat(measurement(metric("owner_status_dlq_depth")))
            .as("每转发一条死信加一").isEqualTo(before + 1d);
    }

    @Test
    @DisplayName("日志字段口径：原因截断到 64 字，不泄露信用代码与电话")
    void 原因截断口径() {
        String longReason = "原".repeat(120);

        assertThat(OwnerMetrics.truncate(longReason)).hasSize(OwnerMetrics.REASON_LOG_MAX + 1)
            .endsWith("…");
        assertThat(OwnerMetrics.truncate("合同到期")).isEqualTo("合同到期");
        assertThat(OwnerMetrics.truncate(null)).isEmpty();
    }

    private String newKey(String scene) {
        String key = "it-owner-metrics-" + scene + "-" + nextId();
        registerIdempotencyKey(key);
        return key;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metric(String name) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/actuator/metrics/" + name, null, null, null);
        assertThat(response.getStatusCode())
            .as("指标 %s 必须能在端点观察，实际 %s", name, response.getStatusCode())
            .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private double measurement(Map<String, Object> metric) {
        List<Map<String, Object>> measurements =
            (List<Map<String, Object>>) metric.get("measurements");
        assertThat(measurements).isNotEmpty();
        return ((Number) measurements.get(0).get("value")).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> tags(Map<String, Object> metric) {
        List<Map<String, Object>> availableTags =
            (List<Map<String, Object>>) metric.get("availableTags");
        return availableTags.stream().map(tag -> tag.get("tag").toString()).toList();
    }
}
