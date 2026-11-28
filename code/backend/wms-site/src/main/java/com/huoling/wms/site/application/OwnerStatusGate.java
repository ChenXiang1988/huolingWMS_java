package com.huoling.wms.site.application;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.OwnerBlockedException;
import com.huoling.wms.site.domain.SiteException;
import com.huoling.wms.site.domain.SiteOwnerStatus;
import com.huoling.wms.site.domain.SiteOwnerStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 受理前的货主状态闸门（tasks 1.14；design「停用货主如何阻断单仓端新建业务」）。
 *
 * <p>四条判定，顺序固定：
 * <ol>
 *   <li>副本有记录且可用 → <b>放行</b>，并留一条放行日志（谁、什么版本、什么数据时间）；</li>
 *   <li>副本有记录且停用 → <b>拒绝</b>（{@code OWNER_DISABLED}）；</li>
 *   <li>副本无记录 → <b>拒绝</b>（{@code SNAPSHOT_MISSING}）：状态不可读时不放行、不「先写后补」；</li>
 *   <li>副本的数据时间超过陈旧阈值 → <b>拒绝</b>（{@code SNAPSHOT_STALE}）：
 *       阈值内视为「最近一次权威状态」，Hub 短时抖动不该让全仓停摆；超过阈值等于长期失联，
 *       放行就是给可能已停用的货主开单。</li>
 * </ol>
 *
 * <p><b>开关</b> {@code wms.owner.blocking.enabled} 默认 {@code false}：副本覆盖率确认前一律放行，
 * 这样上线顺序可以是「先只消费不拦截，再打开闸门」（design「灰度开关」），回滚也只是一次开关翻转。
 *
 * <p><b>只在新建受理入口调用</b>：本类不挂在执行链路、定时任务或存量单据的推进路径上——
 * 停用不阻断存量单据与任务（OI-11、spec「存量继续」）。具体接线到哪张单据由单据域变更完成，
 * 本变更只交付组件与判定。
 */
@Service
public class OwnerStatusGate {

    private static final Logger log = LoggerFactory.getLogger(OwnerStatusGate.class);

    private final SiteOwnerStatusRepository statuses;
    private final SiteProperties properties;
    private final OwnerGateMetrics metrics;

    public OwnerStatusGate(SiteOwnerStatusRepository statuses, SiteProperties properties,
                           OwnerGateMetrics metrics) {
        this.statuses = statuses;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 断言该货主可以接受新建业务。
     *
     * @throws OwnerBlockedException 三种拒绝原因之一；调用方按 {@code reason()} 给文案与出口
     * @throws SiteException         {@code ownerId} 缺失（{@code PARAM_INVALID}）
     */
    public void assertCanAcceptNewBusiness(Long ownerId) {
        SiteProperties.Owner.Blocking blocking = properties.getOwner().getBlocking();
        if (!blocking.isEnabled()) {
            // 开关关闭：判定不发生，也不读库——开关要能真正回到「不拦截」的状态。
            return;
        }
        if (ownerId == null) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "受理前判定货主状态需要 ownerId");
        }
        SiteOwnerStatus status = statuses.find(ownerId).orElse(null);
        if (status == null) {
            // 新档未下行或消费链路断：拒绝窗口是秒级，比放行一个未知状态安全。
            log.warn("货主状态闸门拒绝（副本无记录） ownerId={}", ownerId);
            throw denied(OwnerBlockedException.snapshotMissing(ownerId));
        }
        if (!status.active()) {
            log.warn("货主状态闸门拒绝（货主已停用） ownerId={} ownerNo={} sourceVersion={}",
                ownerId, status.ownerNo(), status.sourceVersion());
            throw denied(OwnerBlockedException.disabled(ownerId, status.ownerNo()));
        }
        Duration threshold = blocking.getStaleThreshold();
        Instant dataTimestamp = status.dataTimestamp();
        if (dataTimestamp == null || dataTimestamp.isBefore(Instant.now().minus(threshold))) {
            log.warn("货主状态闸门拒绝（副本陈旧） ownerId={} ownerNo={} dataTimestamp={} thresholdSeconds={}",
                ownerId, status.ownerNo(), dataTimestamp, threshold.toSeconds());
            throw denied(OwnerBlockedException.snapshotStale(ownerId, threshold.toSeconds()));
        }
        // 放行留痕：正常路径也要能从日志定位「这单为什么放行、依据哪个版本」。
        log.info("货主状态闸门放行 ownerId={} ownerNo={} status={} sourceVersion={} dataTimestamp={}",
            ownerId, status.ownerNo(), status.status(), status.sourceVersion(), dataTimestamp);
        metrics.allowed();
    }

    /**
     * 记一次拒绝再抛出：拒绝原因的计数与抛出的异常用同一个 {@code reason}，
     * 不会出现「指标说停用、日志说陈旧」的两套口径。
     */
    private OwnerBlockedException denied(OwnerBlockedException blocked) {
        metrics.denied(blocked.reason());
        return blocked;
    }
}
