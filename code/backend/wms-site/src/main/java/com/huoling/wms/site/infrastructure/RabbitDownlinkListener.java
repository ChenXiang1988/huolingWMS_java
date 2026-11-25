package com.huoling.wms.site.infrastructure;

import com.huoling.wms.site.application.SkuSnapshotConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 下行事件监听：把队列消息交给 {@link SkuSnapshotConsumer}（tasks 1.10、1.12）。
 *
 * <p>灰度开关（design「可观测与兼容回滚」）：{@code wms.archive.downlink.enabled} 为假时整个监听
 * 组件不装配，单仓只展示已有缓存与待刷新提示，不会连消息中间件。
 *
 * <p>拓扑（交换、队列、绑定）由部署配置提供，代码不声明交换类型：声明错了会与线上既有交换冲突。
 * 事件标识取消息属性 {@code messageId}，事件类型取头 {@code eventType}，两者由投递器写入。
 */
@Component
@ConditionalOnProperty(prefix = "wms.archive.downlink", name = "enabled", havingValue = "true")
public class RabbitDownlinkListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitDownlinkListener.class);

    private final SkuSnapshotConsumer consumer;

    public RabbitDownlinkListener(SkuSnapshotConsumer consumer) {
        this.consumer = consumer;
    }

    @RabbitListener(queues = "${wms.archive.downlink.queue:wms.archive.sku.site}")
    public void onMessage(Message message) {
        String eventId = message.getMessageProperties().getMessageId();
        Object eventType = message.getMessageProperties().getHeaders().get("eventType");
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            consumer.consume(eventId, eventType == null ? null : String.valueOf(eventType), payload);
        } catch (RuntimeException e) {
            // 消费失败不吞消息：抛出后由容器按重试策略重回队列，落库幂等由消费方的去重与版本规则保证。
            log.warn("下行事件 {} 消费失败：{}", eventId, e.getMessage());
            throw e;
        }
    }
}
