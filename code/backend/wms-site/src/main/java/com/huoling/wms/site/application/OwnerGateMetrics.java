package com.huoling.wms.site.application;

import com.huoling.wms.site.domain.OwnerBlockedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 受理前货主状态闸门的指标（tasks 1.18；design「可观测与兼容回滚」的
 * {@code site_owner_cache_miss_total}）。
 *
 * <p>三个指标回答三个不同的问题：
 * <ul>
 *   <li>{@code site_owner_gate_allowed_total}：闸门放行了多少单。它与拒绝数放在一起才能算出
 *       「拦截率」，只有拒绝数看不出闸门是不是把全仓都挡住了；</li>
 *   <li>{@code site_owner_gate_denied_total{reason}}：拒绝按原因分开。
 *       三种原因的处置完全不同——{@code OWNER_DISABLED} 要找货主，{@code SNAPSHOT_MISSING}
 *       要查消费链路，{@code SNAPSHOT_STALE} 要查同步延迟；混成一个计数等于没有信息；</li>
 *   <li>{@code site_owner_cache_miss_total}：副本未命中（{@code SNAPSHOT_MISSING}）单列，
 *       与设计章的指标名逐字对应。它是「新档还没下行」与「消费链路断了」的共同表征，
 *       单独一条曲线能在故障初期就看出苗头，不用等拒绝总量变化。</li>
 * </ul>
 *
 * <p>指标只在这里注册：闸门每次判定无论放行还是拒绝都调一次，不在判定逻辑里散落计数器。
 */
@Component
public class OwnerGateMetrics {

    private static final String DENIED = "site_owner_gate_denied_total";
    private static final String ALLOWED = "site_owner_gate_allowed_total";
    private static final String CACHE_MISS = "site_owner_cache_miss_total";

    private final MeterRegistry registry;

    public OwnerGateMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 放行一次：闸门判定通过，业务可以继续。 */
    public void allowed() {
        Counter.builder(ALLOWED).register(registry).increment();
    }

    /**
     * 拒绝一次：按原因打标签，同时把「副本未命中」单列一条。
     *
     * <p>标签取枚举名（{@code OWNER_DISABLED} / {@code SNAPSHOT_MISSING} / {@code SNAPSHOT_STALE}），
     * 与设计章和前端文案用的是同一组取值，看板不用再维护一张映射表。
     */
    public void denied(OwnerBlockedException.Reason reason) {
        String tag = reason == null ? "UNKNOWN" : reason.name();
        Counter.builder(DENIED).tag("reason", tag).register(registry).increment();
        if (reason == OwnerBlockedException.Reason.SNAPSHOT_MISSING) {
            Counter.builder(CACHE_MISS).register(registry).increment();
        }
    }
}
