package com.huoling.wms.masterdata.archive.infrastructure;

import com.huoling.wms.masterdata.archive.application.ArchiveEventTransport;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 下行事件的 RabbitMQ 传输实现（01 选型表：消息用 RabbitMQ）。
 *
 * <p>交换与路由键由部署配置提供（design「幂等与异步事件」），代码里只留占位默认值；
 * 事件标识同时放进消息属性 {@code messageId}，消费方可直接按它去重，无需解析载荷。
 *
 * <p>连接由 Spring 的自动配置按 {@code spring.rabbitmq.*} 建立，发送时才建连：
 * 中间件不可用时在这里抛出，由投递器记失败并按退避重投，不影响权威档案状态。
 */
@Component
public class RabbitArchiveEventTransport implements ArchiveEventTransport {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public RabbitArchiveEventTransport(
            RabbitTemplate rabbitTemplate,
            @Value("${wms.archive.outbox.exchange:wms.archive.events}") String exchange,
            @Value("${wms.archive.outbox.routing-key:wms.archive.sku}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    @Override
    public void publish(ArchiveOutboxMessage message) {
        MessagePostProcessor headers = amqpMessage -> {
            amqpMessage.getMessageProperties().setMessageId(message.eventId());
            amqpMessage.getMessageProperties().setHeader("eventType", message.eventType());
            amqpMessage.getMessageProperties().setHeader("version", message.version());
            return amqpMessage;
        };
        rabbitTemplate.convertAndSend(exchange, routingKey, message.payloadJson(), headers);
    }
}
