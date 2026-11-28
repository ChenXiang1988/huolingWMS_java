package com.huoling.wms.site.infrastructure;

import com.huoling.wms.site.application.OwnerStatusConsumer;
import com.huoling.wms.site.domain.SiteException;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 货主状态下行的确认与死信（tasks 1.14；design「死信」：消费抛异常 → {@code basicNack(requeue=false)}
 * 进死信队列 {@code wms.owner.status.dlq}）。
 *
 * <p>不连消息中间件：用假的 {@link Channel} 断言两个动作——
 * 成功必须 {@code basicAck}，失败必须 {@code basicNack(requeue=false)}（不是重回原队列）。
 * 「重回原队列」是这条链路上最容易写错的一处：坏载荷会被无限重投，消费线程拖在原地而无人察觉。
 */
class RabbitOwnerStatusListenerTest {

    private static final long DELIVERY_TAG = 42L;
    private static final String EVENT_ID = "OW000001#2";
    private static final String DEAD_LETTER_QUEUE = "wms.owner.status.dlq";

    private OwnerStatusConsumer consumer;
    private Channel channel;
    private RabbitOwnerStatusListener listener;

    @BeforeEach
    void setUp() {
        consumer = mock(OwnerStatusConsumer.class);
        channel = mock(Channel.class);
        listener = new RabbitOwnerStatusListener(consumer, DEAD_LETTER_QUEUE);
    }

    @Test
    @DisplayName("消费成功：确认消息，不产生死信")
    void 消费成功即确认() throws Exception {
        listener.onMessage(message(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("消费失败：拒绝且不重回原队列，由队列的死信交换投进 DLQ")
    void 消费失败转死信() throws Exception {
        doThrow(new SiteException("PARAM_INVALID", "载荷非法"))
            .when(consumer).consume(anyString(), anyString(), anyString());

        listener.onMessage(message(), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        properties.setMessageId(EVENT_ID);
        properties.setHeader("eventType", OwnerStatusConsumer.TYPE_OWNER_STATUS_CHANGED);
        return new Message("{\"eventId\":\"OW000001#2\"}".getBytes(StandardCharsets.UTF_8),
            properties);
    }
}
