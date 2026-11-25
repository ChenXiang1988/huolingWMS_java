package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveAcceptanceRepository;
import com.huoling.wms.masterdata.archive.domain.ArchiveAcceptanceSweep;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 受理超时的补偿扫描（本次变更 tasks 1.18；design「可观测与兼容回滚」的定时补偿同一做法）。
 *
 * <p>解决什么问题：受理执行是异步的，线程池拒绝已被 {@code ArchiveAcceptanceService} 落成失败，
 * 但进程重启、执行线程被中断等情况下受理单会停在 {@code PROCESSING} 无人收尾，
 * 前端轮询到 60 秒上限也拿不到结论。这里按固定间隔扫「状态仍是处理中且更新时间已超时」的记录，
 * 统一落成 {@code FAILED} 并写契约错误码，使受理查询必然收敛到成功或失败两态。
 *
 * <p>超时时长取默认三百秒而不是轮询上限六十秒：留出足够余量，避免把仍在执行的受理单误判为超时
 * （误判会让受理结果显示失败、业务其实成功）。判定与收尾都只从 {@code PROCESSING} 出发，
 * 多副本并发扫同一条记录不会覆盖已终结的结果。
 */
@Component
public class ArchiveAcceptanceCompensator {

    private static final Logger log = LoggerFactory.getLogger(ArchiveAcceptanceCompensator.class);

    /** 默认超时时长（秒）：远大于契约的六十秒轮询上限。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    /** 超时受理单的失败说明：受理查询按错误码与这句说明回给调用方。 */
    static final String TIMEOUT_MESSAGE = "受理执行超时，请按同一幂等键重试或带追踪编号报障";

    private final ArchiveAcceptanceSweep sweep;
    private final ArchiveAcceptanceRepository acceptances;
    private final int batchSize;
    private final long timeoutSeconds;

    public ArchiveAcceptanceCompensator(
            ArchiveAcceptanceSweep sweep,
            ArchiveAcceptanceRepository acceptances,
            @Value("${wms.archive.acceptance.sweep-batch-size:100}") int batchSize,
            @Value("${wms.archive.acceptance.timeout-seconds:300}") long timeoutSeconds) {
        this.sweep = sweep;
        this.acceptances = acceptances;
        this.batchSize = batchSize <= 0 ? 100 : batchSize;
        this.timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    /** 定时扫描：间隔由部署配置给出，默认六十秒。 */
    @Scheduled(fixedDelayString = "${wms.archive.acceptance.sweep-interval-ms:60000}")
    public void sweep() {
        int failed = sweepOnce();
        if (failed > 0) {
            log.info("受理超时补偿完成，本次收尾 {} 条", failed);
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
                acceptances.markFailed(acceptNo, ArchiveErrorCodes.DEPENDENCY_UNAVAILABLE,
                    TIMEOUT_MESSAGE, now);
                failed++;
            } catch (RuntimeException e) {
                // 单条失败不影响本轮其他条目：下一轮还会再扫到它。
                log.warn("受理单 {} 的超时收尾失败，下一轮重试", acceptNo, e);
            }
        }
        return failed;
    }
}
