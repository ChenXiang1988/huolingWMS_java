package com.huoling.wms.site.infrastructure;

import com.huoling.wms.site.application.OwnerStatusConsumer;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 货主状态下行监听（tasks 1.14；契约 1.9、design「死信」）。
 *
 * <p>灰度开关 {@code wms.owner.downlink.enabled} 为假时整个监听组件不装配：单仓端不连消息中间件，
 * 与既有商品档案下行（{@code wms.archive.downlink.enabled}）各开各的，互不牵连。
 *
 * <p><b>手工确认</b>：成功即 {@code basicAck}；消费抛异常时 {@code basicNack(requeue=false)}——
 * 消息由队列自身的死信交换投进 {@code wms.owner.status.dlq}（拓扑由部署配置声明），
 * 不重回原队列。原因：坏载荷重投多少次都是坏的，反复重投只会把消费线程拖在原地，
 * 而死信队列里留着原件可按 {@code eventId} 重放。此处同时记错误日志作为告警落点
 * （DLQ 深度 > 0 即告警，见 design「死信」一行）。
 *
 * <p>事件标识取消息属性 {@code messageId}，事件类型取头 {@code eventType}，两者由 Hub 的投递器写入。
 */
@Component
@ConditionalOnProperty(prefix = "wms.owner.downlink", name = "enabled", havingValue = "true")
public class RabbitOwnerStatusListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitOwnerStatusListener.class);

    private final OwnerStatusConsumer consumer;
    private final String deadLetterQueue;

    public RabbitOwnerStatusListener(OwnerStatusConsumer consumer,
                                     @Value("${wms.owner.downlink.dead-letter-queue:wms.owner.status.dlq}")
                                     String deadLetterQueue) {
        this.consumer = consumer;
        this.deadLetterQueue = deadLetterQueue;
    }

    @RabbitListener(queues = "${wms.owner.downlink.queue:wms.owner.status.site}", ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = message.getMessageProperties().getMessageId();
        Object eventType = message.getMessageProperties().getHeaders().get("eventType");
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            consumer.consume(eventId, eventType == null ? null : String.valueOf(eventType), payload);
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException e) {
            log.error("货主状态下行消息 {} 消费失败，拒绝并转入死信队列 {}（DLQ 深度大于 0 即告警）：{}",
                eventId, deadLetterQueue, e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
