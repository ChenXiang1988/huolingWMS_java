package com.huoling.wms.site.application;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.domain.SiteAcceptanceRepository;
import com.huoling.wms.site.domain.SiteAcceptanceSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 单仓受理超时的补偿扫描（本次变更 tasks 1.18）。
 *
 * <p>单仓写接口在请求内同步执行，正常路径不会留下处理中的受理单；进程中断这类异常路径会留下，
 * 而 API-017 只按受理号回答状态，没人收尾就永远查不到结论。这里按固定间隔把超时的处理中受理单
 * 收尾成 {@code FAILED} 并写契约错误码，与 Hub 侧同一做法、同一超时口径。
 *
 * <p>收尾只从 {@code PROCESSING} 出发：多副本或与正常路径并发时不会覆盖已终结的结果。
 */
@Component
public class SiteAcceptanceCompensator {

    private static final Logger log = LoggerFactory.getLogger(SiteAcceptanceCompensator.class);

    /** 默认超时时长（秒）：远大于契约的六十秒轮询上限。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    /** 超时受理单的失败说明。 */
    static final String TIMEOUT_MESSAGE = "受理执行超时，请按同一幂等键重试或带追踪编号报障";

    private final SiteAcceptanceSweep sweep;
    private final SiteAcceptanceRepository acceptances;
    private final int batchSize;
    private final long timeoutSeconds;

    public SiteAcceptanceCompensator(
            SiteAcceptanceSweep sweep,
            SiteAcceptanceRepository acceptances,
            @Value("${wms.site.acceptance.sweep-batch-size:100}") int batchSize,
            @Value("${wms.site.acceptance.timeout-seconds:300}") long timeoutSeconds) {
        this.sweep = sweep;
        this.acceptances = acceptances;
        this.batchSize = batchSize <= 0 ? 100 : batchSize;
        this.timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    /** 定时扫描：间隔由部署配置给出，默认六十秒。 */
    @Scheduled(fixedDelayString = "${wms.site.acceptance.sweep-interval-ms:60000}")
    public void sweep() {
        int failed = sweepOnce();
        if (failed > 0) {
            log.info("单仓受理超时补偿完成，本次收尾 {} 条", failed);
        }
    }

    /**
     * 扫一轮超时的处理中受理单。
     *
     * @return 本轮收尾的条数
     */
    public int sweepOnce() {
        Instant now = Instant.now();
        Instant deadline = now.minusSeconds(timeoutSeconds);
        List<String> stale = sweep.findProcessingBefore(deadline, batchSize);
        int failed = 0;
        for (String acceptNo : stale) {
            try {
                acceptances.markFailed(acceptNo, ArchiveContractCodes.DEPENDENCY_UNAVAILABLE,
                    TIMEOUT_MESSAGE, now);
                failed++;
            } catch (RuntimeException e) {
                log.warn("受理单 {} 的超时收尾失败，下一轮重试", acceptNo, e);
            }
        }
        return failed;
    }
}
