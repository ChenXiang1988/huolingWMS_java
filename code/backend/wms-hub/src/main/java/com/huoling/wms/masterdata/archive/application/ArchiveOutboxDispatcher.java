package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.common.owner.OwnerStatusDeliveryObserver;
import com.huoling.wms.masterdata.archive.domain.ArchiveEventTransport;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxRepository;
import com.huoling.wms.masterdata.archive.domain.OutboxRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地消息投递器（本次变更 tasks 1.12、1.13；design「幂等与异步事件」、22 T-05 / T-07）。
 *
 * <p>按固定间隔扫描 {@code hub_outbox} 的待投递记录，逐条抢占后发往消息中间件：
 * 抢占用条件更新，多副本只有一个实例能发出同一条消息；发送失败记 {@code FAILED}、
 * 累计重试次数并按指数退避重投，不阻塞业务线程，也不回滚已提交的权威档案。
 *
 * <p>路由键由 {@link OutboxRouting} 按 {@code event_type} 前缀解析，本类不写死任何一个键：
 * {@code OWNER_*} 类事件（货主状态）走 {@code wms.owner.status}，其余仍走商品档案路由键。
 *
 * <p>死信与告警：货主状态事件失败到阈值（{@code wms.owner.outbox.dead-letter-threshold}，缺省 5 次）
 * 时，把该条消息转发进死信队列 {@code wms.owner.status.dlq} 并按 {@code eventId} 告警——
 * 死信队列供人工重放，原记录继续退避重投，两条路径都不静默。阈值按「恰好等于」判定，
 * 一条消息只转发一次；转发失败只记日志，不改投递节奏。
 *
 * <p>至少一次投递：重复投递由消费方按事件标识去重（契约「事件与回调」）。
 *
 * <p>观测：货主状态事件在「投递成功」与「转发死信成功」两个时点通过
 * {@link OwnerStatusDeliveryObserver} 报告，供 design 的 {@code owner_status_downlink_lag_seconds}
 * 与 {@code owner_status_dlq_depth} 落点使用。端口放 common、实现在货主档案包，
 * 两边都不认识对方的类（AR-11）；观测异常一律自吞，不改变投递结果。
 */
@Component
public class ArchiveOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ArchiveOutboxDispatcher.class);

    /** 载荷里的 {@code changedAt}（ISO-8601 字符串）：用于算下行延迟，不需要反序列化整个载荷。 */
    private static final Pattern PAYLOAD_CHANGED_AT =
        Pattern.compile("\"changedAt\"\\s*:\\s*\"([^\"]+)\"");

    private final ArchiveOutboxRepository outboxRepository;
    private final ArchiveEventTransport transport;
    private final int batchSize;
    private final int deadLetterThreshold;
    private final OwnerStatusDeliveryObserver deliveryObserver;

    public ArchiveOutboxDispatcher(ArchiveOutboxRepository outboxRepository,
                                   ArchiveEventTransport transport,
                                   @Value("${wms.archive.outbox.batch-size:50}") int batchSize,
                                   @Value("${wms.owner.outbox.dead-letter-threshold:5}")
                                   int deadLetterThreshold,
                                   OwnerStatusDeliveryObserver deliveryObserver) {
        this.outboxRepository = outboxRepository;
        this.transport = transport;
        this.batchSize = batchSize <= 0 ? 50 : batchSize;
        this.deadLetterThreshold = deadLetterThreshold <= 0 ? 5 : deadLetterThreshold;
        this.deliveryObserver = deliveryObserver;
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
                notifyDelivered(message);
            } catch (RuntimeException e) {
                log.warn("下行消息 {} 投递失败，按退避重试：{}", message.eventId(), e.getMessage());
                int retries = outboxRepository.markFailed(message.id(), e.getMessage(), Instant.now());
                deadLetterIfExhausted(message, retries, e.getMessage());
            }
        }
        return sent;
    }

    /**
     * 重试到达阈值：转发死信并告警。
     *
     * <p>只对货主状态事件转发（design 只为这类事件定义了 {@code wms.owner.status.dlq}）；
     * 商品档案的下行保持既有行为——记 {@code FAILED}、继续退避重投并告警，不改路由。
     */
    private void deadLetterIfExhausted(ArchiveOutboxMessage message, int retries, String error) {
        if (!OutboxRouting.isOwnerEvent(message.eventType())) {
            return;
        }
        if (retries != deadLetterThreshold) {
            return;
        }
        log.error("货主状态事件投递重试耗尽，转入死信队列 eventId={} ownerId={} retries={} error={}",
            message.eventId(), message.ownerId(), retries, error);
        try {
            transport.deadLetter(message, error);
            notifyDeadLettered(message);
        } catch (RuntimeException dlqFailure) {
            log.error("货主状态事件死信转发失败，需人工按 eventId 重放 eventId={}：{}",
                message.eventId(), dlqFailure.getMessage());
        }
    }

    /**
     * 投递成功后的观测回调（design 的 {@code owner_status_downlink_lag_seconds}）。
     *
     * <p>自带 try/catch 是硬要求：本方法在投递器的主 try 块里，向上抛会被当成「投递失败」
     * 去 markFailed——观测失败不能反过来改投递结果。
     *
     * <p>只对货主状态事件回调，商品档案下行不带这项指标。
     */
    private void notifyDelivered(ArchiveOutboxMessage message) {
        if (deliveryObserver == null || !OutboxRouting.isOwnerEvent(message.eventType())) {
            return;
        }
        try {
            deliveryObserver.delivered(message.eventId(), changedAtOf(message));
        } catch (RuntimeException e) {
            log.warn("货主状态下行延迟上报失败 eventId={}：{}", message.eventId(), e.getMessage());
        }
    }

    /** 死信转发成功后的观测回调（{@code owner_status_dlq_depth}），异常同样不外抛。 */
    private void notifyDeadLettered(ArchiveOutboxMessage message) {
        if (deliveryObserver == null) {
            return;
        }
        try {
            deliveryObserver.deadLettered(message.eventId());
        } catch (RuntimeException e) {
            log.warn("货主状态死信深度上报失败 eventId={}：{}", message.eventId(), e.getMessage());
        }
    }

    /**
     * 事件的 {@code changedAt}：优先取载荷里的权威产生时间，取不到才回落到 {@code created_at}。
     *
     * <p>为什么不用 ObjectMapper：投递器只关心一个时间字段，为此引入反序列化会把
     * 「载荷字段漂移」变成投递失败。这里读不到就回落，回落值仍然是同一事务写入的行时间，
     * 与 {@code changedAt} 只差微秒级——延迟指标不会因此失真。
     */
    private static Instant changedAtOf(ArchiveOutboxMessage message) {
        String payload = message.payloadJson();
        if (payload != null) {
            Matcher matcher = PAYLOAD_CHANGED_AT.matcher(payload);
            if (matcher.find()) {
                try {
                    return Instant.parse(matcher.group(1));
                } catch (DateTimeParseException ignored) {
                    // 载荷里的时间不是 ISO-8601：按回落值算，不把观测问题升级成投递问题。
                }
            }
        }
        return message.createdAt();
    }
}
