package com.huoling.wms.masterdata.acceptance.domain;

import java.time.Instant;
import java.util.List;

/**
 * 受理记录的超时扫描端口（design「事务边界与并发控制」的补偿扫描）。
 *
 * <p>为什么单开一个端口而不是给 {@link AcceptanceRepository} 加方法：受理仓储的接口
 * 已经被受理服务与测试替身共同实现，往里加方法会把「只做写序验证」的替身一起拖下水；
 * 扫描是补偿任务专用的读，独立成口更清楚，也让受理服务只依赖自己用得到的方法。
 */
public interface AcceptanceSweep {

    /**
     * 取仍停在 {@code PROCESSING} 且更新时间早于给定时刻的受理号，按更新时间升序。
     *
     * @param deadline 超时判定时刻，早于该时刻仍未终结即为陈旧
     * @param limit    单轮上限，避免一次扫全表
     */
    List<String> findProcessingBefore(Instant deadline, int limit);
}
