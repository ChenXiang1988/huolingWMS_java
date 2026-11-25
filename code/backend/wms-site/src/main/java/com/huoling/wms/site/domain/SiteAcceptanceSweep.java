package com.huoling.wms.site.domain;

import java.time.Instant;
import java.util.List;

/**
 * 单仓受理记录的超时扫描端口（本次变更 tasks 1.18）。
 *
 * <p>单仓的写接口在请求内同步执行，正常路径不会停在处理中；但进程在「已落受理记录、
 * 业务动作未提交」之间退出时，记录会永久停在 {@code PROCESSING}，API-017 也就永远查不到结论。
 * 扫描按更新时间找出这类记录，收尾成失败。
 *
 * <p>单开端口而不是给 {@link SiteAcceptanceRepository} 加方法：受理服务的测试替身只实现写入路径，
 * 往里加读方法会把替身一起拖下水，也让受理服务依赖自己用不到的扫描。
 */
public interface SiteAcceptanceSweep {

    /**
     * 取仍停在 {@code PROCESSING} 且更新时间早于给定时刻的受理号，按更新时间升序。
     *
     * @param deadline 超时判定时刻
     * @param limit    单轮上限
     */
    List<String> findProcessingBefore(Instant deadline, int limit);
}
