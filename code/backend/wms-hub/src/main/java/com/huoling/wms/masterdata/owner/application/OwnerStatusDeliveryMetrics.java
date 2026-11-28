package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.common.owner.OwnerStatusDeliveryObserver;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 货主状态下行投递的观测实现（tasks 1.18；design「可观测与兼容回滚」的两项指标）。
 *
 * <p>落点为什么在这里：指标语义属货主档案，实现就必须在 {@code masterdata.owner}；
 * 而投递器在 {@code masterdata.archive}，两个包不得互相依赖（AR-11）。
 * 端口 {@link OwnerStatusDeliveryObserver} 下沉到 common，投递器只认端口，本类只认指标。
 *
 * <p>两个方法都不抛异常：观测是旁路，不能让一次成功的投递或一次成功的死信转发
 * 因为指标注册失败而变成失败。投递器侧还有一层 try/catch，两层都只是兜底。
 */
@Component
public class OwnerStatusDeliveryMetrics implements OwnerStatusDeliveryObserver {

    private final OwnerMetrics metrics;

    public OwnerStatusDeliveryMetrics(OwnerMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 投递成功：按 {@code now - changedAt} 上报延迟。
     *
     * <p>{@code changedAt} 缺失时不上报而不是按零处理：把「不知道」画成 0 会让看板
     * 在数据缺失时显示「一切正常」。
     */
    @Override
    public void delivered(String eventId, Instant changedAt) {
        if (changedAt == null) {
            return;
        }
        Duration lag = Duration.between(changedAt, Instant.now());
        metrics.statusDownlinkLag(lag.isNegative() ? Duration.ZERO : lag);
    }

    /** 死信转发成功：死信深度加一并上报。 */
    @Override
    public void deadLettered(String eventId) {
        metrics.deadLetterForwarded();
    }
}
