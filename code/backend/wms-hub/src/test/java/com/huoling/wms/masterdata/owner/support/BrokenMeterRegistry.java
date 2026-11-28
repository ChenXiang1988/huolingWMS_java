package com.huoling.wms.masterdata.owner.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 指标后端不可用的替身：新建任何计数器都抛 {@link IllegalStateException}。
 *
 * <p>用来验证「观测异常不改业务结果」：Micrometer 侧抛错时，受理执行、受理票据与越权拒绝
 * 都必须保持原来的结果——成功不得翻成失败、403 不得被顶成 500。
 *
 * <p>只覆写计数器工厂：货主档案的三处观测落点（受理计数、写动作计数与耗时、数据域拒绝计数）
 * 的第一步都是计数器注册，计数器建不出来即代表「指标后端整体不可用」，
 * 同一段 {@code try} 里的计时器也就走不到——这正是真实故障的形状。
 */
public final class BrokenMeterRegistry extends SimpleMeterRegistry {

    /** 抛出的说明：与真实 Micrometer 后端故障一样，是被观测方不该感知的外部问题。 */
    public static final String REASON = "meter registry unavailable";

    @Override
    protected Counter newCounter(Meter.Id id) {
        throw new IllegalStateException(REASON);
    }
}
