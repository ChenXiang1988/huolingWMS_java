package com.huoling.wms.common.owner;

import java.time.Instant;

/**
 * 货主状态下行的投递观测端口（design「可观测与兼容回滚」的两项指标）。
 *
 * <p>为什么需要这个端口：投递器在 {@code masterdata.archive} 包，货主状态的指标实现必须在
 * {@code masterdata.owner} 包，而架构断言 AR-11 禁止两个包双向依赖。把观测做成一个只依赖 JDK 类型的
 * 端口放 common，投递器向端口报告、货主档案侧实现端口，两侧都不认识对方的类。
 *
 * <p>实现方必须是幂等且不抛异常的：观测失败不该把一次成功的投递变成失败。端口方法无返回值，
 * 调用方（投递器）在 {@code try/catch} 里调用，异常只记日志。
 *
 * <p>只对货主状态事件报告（{@code event_type} 以 {@code OWNER} 开头）；商品档案的下行不带这两项指标。
 */
public interface OwnerStatusDeliveryObserver {

    /**
     * 一条货主状态事件投递成功。
     *
     * @param eventId   事件标识（{@code ownerNo#version}），用于日志定位
     * @param changedAt 权威数据的产生时间（载荷里的 {@code changedAt}，不是投递时间）
     */
    void delivered(String eventId, Instant changedAt);

    /**
     * 一条货主状态事件重试耗尽、已转发进死信队列。
     *
     * @param eventId 事件标识，人工重放时按它定位
     */
    void deadLettered(String eventId);
}
