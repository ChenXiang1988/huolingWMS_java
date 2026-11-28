package com.huoling.wms.site.domain;

/**
 * 下行事件消费端口（design「幂等与异步事件」：审核通过后经消息中间件通知单仓）。
 *
 * <p>端口归 domain（03 四层表：infrastructure 只允许依赖本模块 domain 的接口），
 * 监听器只按本接口调用，实现在 application 层，便于脱离消息中间件做消费逻辑的单元测试。
 *
 * <p>实现方必须保证至少一次投递下的幂等：重复投递不得重复应用，版本落后即丢弃。
 */
public interface DownlinkEventConsumer {

    /**
     * 消费一条下行消息。
     *
     * @param eventId     事件标识，去重键
     * @param eventType   事件类型；未知类型按丢弃处理
     * @param payloadJson 下行摘要
     * @return 是否真的应用了这条消息（版本落后、状态不生效或非本仓都返回 {@code false}）
     */
    boolean consume(String eventId, String eventType, String payloadJson);
}
