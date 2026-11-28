package com.huoling.wms.masterdata.archive.domain;

/**
 * 下行事件传输口（design「幂等与异步事件」：经消息中间件发往单仓端，交换与路由键由部署配置提供）。
 *
 * <p>端口归 domain（03 四层表：infrastructure 只允许依赖本模块 domain 的接口），
 * 实现放 infrastructure，调用方 {@code ArchiveOutboxDispatcher} 按本接口注入。
 *
 * <p>抽成接口是为了让投递器与具体中间件解耦：交换与路由键由配置给出，
 * 投递器只关心「发出去没有」，不关心怎么发。
 */
public interface ArchiveEventTransport {

    /**
     * 发送一条本地消息。
     *
     * <p>路由键由 {@link OutboxRouting} 按 {@code event_type} 前缀解析：
     * {@code SKU_*} 走商品档案路由键，{@code OWNER_*} 走货主状态路由键。
     *
     * @throws RuntimeException 发送失败；由投递器记录重试次数并按退避重投
     */
    void publish(ArchiveOutboxMessage message);

    /**
     * 死信转发：把重试耗尽的消息投到死信队列，供按 {@code eventId} 重放与告警。
     *
     * <p>只在货主状态事件上调用（design 只为本类事件定义了 {@code wms.owner.status.dlq}）；
     * 商品档案的下行不在本变更的处理范围，投递器对它们只记失败与告警，不改路由。
     *
     * @param reason 失败原因摘要，写进消息头供排障
     * @throws RuntimeException 转发失败；由投递器记日志告警，不影响业务事务
     */
    void deadLetter(ArchiveOutboxMessage message, String reason);
}
