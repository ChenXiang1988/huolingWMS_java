package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 本地消息投递器（本次变更 tasks 1.12；design「幂等与异步事件」、22 T-05）。
 *
 * <p>按固定间隔扫描 {@code hub_outbox} 的待投递记录，逐条抢占后发往消息中间件：
 * 抢占用条件更新，多副本只有一个实例能发出同一条消息；发送失败记 {@code FAILED}、
 * 累计重试次数并按指数退避重投，不阻塞业务线程，也不回滚已提交的权威档案。
 *
 * <p>至少一次投递：重复投递由消费方按事件标识去重（契约「事件与回调」）。
 */
@Component
public class ArchiveOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ArchiveOutboxDispatcher.class);

    private final ArchiveOutboxRepository outboxRepository;
    private final ArchiveEventTransport transport;
    private final int batchSize;

    public ArchiveOutboxDispatcher(ArchiveOutboxRepository outboxRepository,
                                   ArchiveEventTransport transport,
                                   @Value("${wms.archive.outbox.batch-size:50}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.transport = transport;
        this.batchSize = batchSize <= 0 ? 50 : batchSize;
    }

    /** 定时扫描：间隔由部署配置给出，默认三十秒。 */
    @Scheduled(fixedDelayString = "${wms.archive.outbox.interval-ms:30000}")
    public void dispatch() {
        int sent = dispatchOnce();
        if (sent > 0) {
            log.info("下行消息投递完成，本次成功 {} 条", sent);
        }
    }

    /**
     * 扫一轮待投递记录。
     *
     * @return 本轮投递成功的条数
     */
    public int dispatchOnce() {
        Instant now = Instant.now();
        List<ArchiveOutboxMessage> pending = outboxRepository.findDispatchable(batchSize, now);
        int sent = 0;
        for (ArchiveOutboxMessage message : pending) {
            if (!outboxRepository.claim(message.id())) {
                // 已被其它实例抢走或还没到重试时间：跳过，不重复投递。
                continue;
            }
            try {
                transport.publish(message);
                outboxRepository.markSent(message.id(), Instant.now());
                sent++;
            } catch (RuntimeException e) {
                log.warn("下行消息 {} 投递失败，按退避重试：{}", message.eventId(), e.getMessage());
                outboxRepository.markFailed(message.id(), e.getMessage(), Instant.now());
            }
        }
        return sent;
    }
}
