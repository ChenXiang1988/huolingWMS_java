package com.huoling.wms.masterdata.archive.infrastructure;

import com.huoling.wms.masterdata.archive.domain.ArchiveEventTransport;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.OutboxRouting;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 下行事件的 RabbitMQ 传输实现（01 选型表：消息用 RabbitMQ）。
 *
 * <p>交换与路由键由部署配置提供（design「幂等与异步事件」），代码里只留占位默认值；
 * 路由键按 {@code event_type} 前缀解析（{@link OutboxRouting}）：一条交换承载商品档案与货主状态两类下行。
 *
 * <p>事件标识同时放进消息属性 {@code messageId}，消费方可直接按它去重，无需解析载荷。
 *
 * <p>连接由 Spring 的自动配置按 {@code spring.rabbitmq.*} 建立，发送时才建连：
 * 中间件不可用时在这里抛出，由投递器记失败并按退避重投，不影响权威档案状态。
 *
 * <p>死信队列不做声明：拓扑（交换、队列、绑定、DLX）由部署配置提供，代码声明错了会与线上既有拓扑冲突。
 * 死信走默认交换（{@code ""}）按队列名投递，队列不存在时消息不可路由——这种情况由投递器的告警日志兜住，
 * 不让「死信队列未配置」把一次投递失败放大成业务失败。
 */
@Component
public class RabbitArchiveEventTransport implements ArchiveEventTransport {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String skuRoutingKey;
    private final String ownerRoutingKey;
    private final String ownerDeadLetterQueue;

    public RabbitArchiveEventTransport(
            RabbitTemplate rabbitTemplate,
            @Value("${wms.archive.outbox.exchange:wms.archive.events}") String exchange,
            @Value("${wms.archive.outbox.routing-key:wms.archive.sku}") String skuRoutingKey,
            @Value("${wms.owner.outbox.routing-key:wms.owner.status}") String ownerRoutingKey,
            @Value("${wms.owner.outbox.dead-letter-queue:wms.owner.status.dlq}")
            String ownerDeadLetterQueue) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.skuRoutingKey = skuRoutingKey;
        this.ownerRoutingKey = ownerRoutingKey;
        this.ownerDeadLetterQueue = ownerDeadLetterQueue;
    }

    @Override
    public void publish(ArchiveOutboxMessage message) {
        rabbitTemplate.convertAndSend(exchange,
            OutboxRouting.routingKey(message.eventType(), skuRoutingKey, ownerRoutingKey),
            message.payloadJson(), headersOf(message));
    }

    @Override
    public void deadLetter(ArchiveOutboxMessage message, String reason) {
        MessagePostProcessor headers = amqpMessage -> {
            headersOf(message).postProcessMessage(amqpMessage);
            amqpMessage.getMessageProperties().setHeader("deadLetterReason",
                reason == null ? "投递重试耗尽" : reason);
            amqpMessage.getMessageProperties().setHeader("deadLetterQueue", ownerDeadLetterQueue);
            return amqpMessage;
        };
        rabbitTemplate.convertAndSend("", ownerDeadLetterQueue, message.payloadJson(), headers);
    }

    private MessagePostProcessor headersOf(ArchiveOutboxMessage message) {
        return amqpMessage -> {
            amqpMessage.getMessageProperties().setMessageId(message.eventId());
            amqpMessage.getMessageProperties().setHeader("eventType", message.eventType());
            amqpMessage.getMessageProperties().setHeader("version", message.version());
            return amqpMessage;
        };
    }
}
