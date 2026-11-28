package com.huoling.wms.site.application;

import com.huoling.wms.site.domain.OwnerBlockedException;
import com.huoling.wms.site.support.SiteDbTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受理前货主状态闸门（tasks 1.14；design「失败模式与降级」四行）。
 *
 * <p>四条判定各一个用例：副本可用放行、副本停用拒绝、副本无记录拒绝、
 * 副本数据时间超过阈值拒绝；阈值取 1 小时（设计缺省），并另用一个更短阈值证明它可配。
 */
@SpringBootTest(classes = com.huoling.wms.site.SiteApplication.class,
    properties = {"wms.site.id=1001", "wms.owner.blocking.enabled=true"})
class OwnerStatusGateIT extends SiteDbTestSupport {

    private static final String DENIED = "site_owner_gate_denied_total";
    private static final String ALLOWED = "site_owner_gate_allowed_total";
    private static final String CACHE_MISS = "site_owner_cache_miss_total";

    @Autowired
    private OwnerStatusGate gate;

    @Autowired
    private MeterRegistry meterRegistry;

    /** 指标计数：不存在时按 0 读，断言统一用「操作前 + 1」，避免同 context 其它用例的累加干扰。 */
    private double count(String name, String... reasonTag) {
        Search search = meterRegistry.find(name);
        if (reasonTag.length == 2) {
            search = search.tag(reasonTag[0], reasonTag[1]);
        }
        Counter counter = search.counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    @DisplayName("副本可用且未陈旧：放行")
    void 可用货主放行() {
        long ownerId = applyOwnerStatus("ACTIVE", 3, Instant.now().minus(Duration.ofMinutes(5)));

        assertThatCode(() -> gate.assertCanAcceptNewBusiness(ownerId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("副本停用：拒绝，原因是货主已停用")
    void 停用货主拒绝() {
        long ownerId = applyOwnerStatus("DISABLED", 4, Instant.now().minus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> gate.assertCanAcceptNewBusiness(ownerId))
            .isInstanceOf(OwnerBlockedException.class)
            .extracting(e -> ((OwnerBlockedException) e).reason())
            .isEqualTo(OwnerBlockedException.Reason.OWNER_DISABLED);
    }

    @Test
    @DisplayName("副本无记录：拒绝，原因是状态不可读（新档未下行或消费链路断）")
    void 副本无记录拒绝() {
        long ownerId = registerOwnerStatus(nextId());

        assertThatThrownBy(() -> gate.assertCanAcceptNewBusiness(ownerId))
            .isInstanceOf(OwnerBlockedException.class)
            .extracting(e -> ((OwnerBlockedException) e).reason())
            .isEqualTo(OwnerBlockedException.Reason.SNAPSHOT_MISSING);
    }

    @Test
    @DisplayName("副本数据时间超过 1 小时：拒绝，原因是副本陈旧（长期失联）")
    void 副本陈旧拒绝() {
        long ownerId = applyOwnerStatus("ACTIVE", 5, Instant.now().minus(Duration.ofMinutes(61)));

        assertThatThrownBy(() -> gate.assertCanAcceptNewBusiness(ownerId))
            .isInstanceOf(OwnerBlockedException.class)
            .extracting(e -> ((OwnerBlockedException) e).reason())
            .isEqualTo(OwnerBlockedException.Reason.SNAPSHOT_STALE);
    }

    @Test
    @DisplayName("闸门指标：三种拒绝按原因各计一条，放行单独计数，副本未命中再多记一条")
    void 指标按原因分记() {
        double deniedDisabled = count(DENIED, "OWNER_DISABLED");
        double deniedMissing = count(DENIED, "SNAPSHOT_MISSING");
        double deniedStale = count(DENIED, "SNAPSHOT_STALE");
        double cacheMiss = count(CACHE_MISS);
        double allowed = count(ALLOWED);

        long disabled = applyOwnerStatus("DISABLED", 8, Instant.now().minus(Duration.ofMinutes(5)));
        long missing = registerOwnerStatus(nextId());
        long stale = applyOwnerStatus("ACTIVE", 9, Instant.now().minus(Duration.ofMinutes(61)));
        long fresh = applyOwnerStatus("ACTIVE", 10, Instant.now().minus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> gate.assertCanAcceptNewBusiness(disabled))
            .isInstanceOf(OwnerBlockedException.class);
        assertThatThrownBy(() -> gate.assertCanAcceptNewBusiness(missing))
            .isInstanceOf(OwnerBlockedException.class);
        assertThatThrownBy(() -> gate.assertCanAcceptNewBusiness(stale))
            .isInstanceOf(OwnerBlockedException.class);
        assertThatCode(() -> gate.assertCanAcceptNewBusiness(fresh)).doesNotThrowAnyException();

        assertThat(count(DENIED, "OWNER_DISABLED")).as("停用拒绝").isEqualTo(deniedDisabled + 1);
        assertThat(count(DENIED, "SNAPSHOT_MISSING")).as("副本无记录").isEqualTo(deniedMissing + 1);
        assertThat(count(DENIED, "SNAPSHOT_STALE")).as("副本陈旧").isEqualTo(deniedStale + 1);
        assertThat(count(CACHE_MISS)).as("副本未命中单列一条，与设计章的指标名对应")
            .isEqualTo(cacheMiss + 1);
        assertThat(count(ALLOWED)).as("放行单独计数，否则算不出拦截率").isEqualTo(allowed + 1);
    }

    @Test
    @DisplayName("阈值边界：59 分钟的副本仍放行，61 分钟拒绝")
    void 阈值边界() {
        long fresh = applyOwnerStatus("ACTIVE", 6, Instant.now().minus(Duration.ofMinutes(59)));
        long stale = applyOwnerStatus("ACTIVE", 7, Instant.now().minus(Duration.ofMinutes(61)));

        assertThatCode(() -> gate.assertCanAcceptNewBusiness(fresh)).doesNotThrowAnyException();
        assertThatThrownBy(() -> gate.assertCanAcceptNewBusiness(stale))
            .isInstanceOf(OwnerBlockedException.class);
    }
}
