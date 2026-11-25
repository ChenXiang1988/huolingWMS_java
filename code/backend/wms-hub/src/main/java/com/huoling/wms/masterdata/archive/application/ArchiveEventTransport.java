package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;

/**
 * 下行事件传输口（design「幂等与异步事件」：经消息中间件发往单仓端，交换与路由键由部署配置提供）。
 *
 * <p>抽成接口是为了让投递器与具体中间件解耦：交换与路由键由配置给出，
 * 投递器只关心「发出去没有」，不关心怎么发。
 */
public interface ArchiveEventTransport {

    /**
     * 发送一条本地消息。
     *
     * @throws RuntimeException 发送失败；由投递器记录重试次数并按退避重投
     */
    void publish(ArchiveOutboxMessage message);
}
