package com.huoling.wms.site.application;

import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.OwnerBlockedException;
import com.huoling.wms.site.domain.SiteOwnerStatus;
import com.huoling.wms.site.domain.SiteOwnerStatusRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 降级分支「Hub 不可达且副本在阈值内 → 放行」的落点（design「失败模式与降级」第三行）。
 *
 * <p>这条分支没法用「把 Hub 停掉」来测——因为闸门根本不调 Hub。设计里被否决的方案就是
 * 「单仓端受理时同步查 Hub」，理由是它会把主数据可用性变成单据受理可用性的前置条件。
 * 因此本类把这条口径翻成两条可判定事实：
 * <ul>
 *   <li><b>结构</b>：闸门的协作者只有本地副本仓储、配置与本地指标，任何 Hub 客户端注入都会让本类变红
 *       （指标组件 {@link OwnerGateMetrics} 只写本进程的 MeterRegistry，不产生任何跨单元调用，
 *       因此不改变「Hub 不可达不影响判定」这条降级口径）；</li>
 *   <li><b>行为</b>：不构造任何 Hub 通道，仅凭一条阈值内的副本就让判定放行。</li>
 * </ul>
 *
 * <p>不连容器：本类只验「依赖面」与「单条判定」，真库行为由 {@link OwnerStatusGateIT} 覆盖。
 */
class OwnerStatusGateDependencyTest {

    private static final long OWNER_ID = 900_001L;

    @Test
    @DisplayName("闸门的协作者只有本地副本与配置：没有 Hub 客户端，Hub 不可达不改变判定")
    void 闸门不依赖Hub() {
        List<String> foreign = new ArrayList<>();
        for (Constructor<?> constructor : OwnerStatusGate.class.getDeclaredConstructors()) {
            for (Class<?> type : constructor.getParameterTypes()) {
                if (isHubType(type)) {
                    foreign.add("构造参数 " + type.getName());
                }
            }
        }
        for (Field field : OwnerStatusGate.class.getDeclaredFields()) {
            if (isHubType(field.getType())) {
                foreign.add("字段 " + field.getType().getName());
            }
        }

        assertThat(foreign)
            .as("闸门 MUST NOT 持有 Hub 的客户端或聚合：停用判定只读本地副本（design 被否方案）")
            .isEmpty();
        assertThat(OwnerStatusGate.class.getDeclaredConstructors())
            .singleElement()
            .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                .as("协作者面固定为「副本仓储 + 配置 + 本地指标」，多一个依赖就要重新评估降级口径")
                .containsExactly(SiteOwnerStatusRepository.class, SiteProperties.class,
                    OwnerGateMetrics.class));
    }

    @Test
    @DisplayName("Hub 不可达但副本在阈值内：只读一条本地记录即放行")
    void 阈值内副本放行() {
        FakeRepository repository = new FakeRepository(Optional.of(snapshot(
            OwnerStatus.ACTIVE, Instant.now().minus(Duration.ofMinutes(10)))));
        OwnerStatusGate gate = new OwnerStatusGate(repository, blockingEnabled(), metrics());

        assertThatCode(() -> gate.assertCanAcceptNewBusiness(OWNER_ID))
            .as("最近一次权威状态是可用，且未超过陈旧阈值 → 放行").doesNotThrowAnyException();
        assertThat(repository.findCalls.get()).as("判定只查一次本地副本").isEqualTo(1);
    }

    @Test
    @DisplayName("Hub 不可达且副本超过阈值：拒绝，不因「没连上 Hub」就放行")
    void 超阈值副本拒绝() {
        FakeRepository repository = new FakeRepository(Optional.of(snapshot(
            OwnerStatus.ACTIVE, Instant.now().minus(Duration.ofMinutes(61)))));
        OwnerStatusGate gate = new OwnerStatusGate(repository, blockingEnabled(), metrics());

        // 长期失联下副本不再可信：放行等于给可能已停用的货主开单，因此必须拒绝且给可重试原因
        assertThatThrownBy(() -> gate.assertCanAcceptNewBusiness(OWNER_ID))
            .isInstanceOf(OwnerBlockedException.class)
            .hasMessageContaining("秒未同步");
    }

    private static boolean isHubType(Class<?> type) {
        return type.getName().startsWith("com.huoling.wms.hub.")
            || type.getName().startsWith("com.huoling.wms.masterdata.owner.")
            || type.getSimpleName().contains("Hub");
    }

    /** 指标替身：用内存注册表，不引入容器也不断言指标值（值由集成用例读端点验证）。 */
    private static OwnerGateMetrics metrics() {
        return new OwnerGateMetrics(new SimpleMeterRegistry());
    }

    private static SiteProperties blockingEnabled() {
        SiteProperties properties = new SiteProperties();
        properties.getOwner().getBlocking().setEnabled(true);
        properties.getOwner().getBlocking().setStaleThreshold(Duration.ofHours(1));
        return properties;
    }

    private static SiteOwnerStatus snapshot(OwnerStatus status, Instant dataTimestamp) {
        return new SiteOwnerStatus(OWNER_ID, "OW" + OWNER_ID, status, 7L, "OW" + OWNER_ID + "#7",
            dataTimestamp, Instant.now());
    }

    /** 本地副本替身：只实现闸门用到的读，写方法不被调用。 */
    private static final class FakeRepository implements SiteOwnerStatusRepository {

        private final Optional<SiteOwnerStatus> row;
        private final AtomicInteger findCalls = new AtomicInteger();

        private FakeRepository(Optional<SiteOwnerStatus> row) {
            this.row = row;
        }

        @Override
        public Optional<SiteOwnerStatus> find(long ownerId) {
            findCalls.incrementAndGet();
            return row.filter(status -> status.ownerId() == ownerId);
        }

        @Override
        public boolean apply(SiteOwnerStatus status) {
            throw new UnsupportedOperationException("闸门是只读判定，不做副本写入");
        }
    }
}
