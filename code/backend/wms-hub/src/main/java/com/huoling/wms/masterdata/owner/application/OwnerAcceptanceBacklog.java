package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.acceptance.domain.AcceptanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 受理积压指标（tasks 1.18、design「可观测与兼容回滚」的 {@code owner_archive_accept_processing}）。
 *
 * <p>为什么单独一个组件：积压是「此刻仍在处理中的受理单条数」，只能按固定间隔读库刷新，
 * 不能挂在请求链路上；写接口的计数已经由 {@link OwnerMetrics} 在受理与执行两处记过，
 * 这里只负责让端点上的数值跟得上库里的事实。
 *
 * <p>只统计货主档案的四个接口号：受理表与商品档案共用，把别人的积压算进来会让
 * 「货主档案写不动了」的告警误导到错误的业务上。
 *
 * <p>刷新失败只记日志、不动指标：一次读库抖动不该让看板掉到零，
 * 也不该把陈旧值当成新值展示；下一轮会重新取到真实条数。
 */
@Component
public class OwnerAcceptanceBacklog {

    private static final Logger log = LoggerFactory.getLogger(OwnerAcceptanceBacklog.class);

    private final AcceptanceRepository acceptances;
    private final OwnerMetrics metrics;

    public OwnerAcceptanceBacklog(AcceptanceRepository acceptances, OwnerMetrics metrics) {
        this.acceptances = acceptances;
        this.metrics = metrics;
    }

    /** 定时刷新受理积压；间隔由部署配置给出，默认六十秒。 */
    @Scheduled(fixedDelayString = "${wms.owner.metrics.backlog-interval-ms:60000}")
    public void refresh() {
        try {
            metrics.acceptanceBacklog(acceptances.countProcessing(OwnerAcceptanceQueries.apiCodes()));
        } catch (RuntimeException e) {
            log.warn("受理积压刷新失败，保留上一轮数值", e);
        }
    }
}
