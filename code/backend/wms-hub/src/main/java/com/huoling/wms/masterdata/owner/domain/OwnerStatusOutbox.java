package com.huoling.wms.masterdata.owner.domain;

/**
 * 货主状态变更的本地消息写入端口（design「事务边界与并发控制」T-07）。
 *
 * <p>状态变更与 {@code hub_outbox} 插入必须在<strong>同一个本地事务</strong>里：
 * 拆开就会出现「货主已停用、单仓端永不感知」，与 T-06 同型。因此本端口只做「写一行消息」，
 * 不做投递——投递器另有其人（{@code ArchiveOutboxDispatcher}），失败按退避重投，不回滚业务状态。
 *
 * <p>为什么端口定义在 owner 包、实现也在 owner 包：货主档案与商品档案 MUST NOT 有编译期依赖
 * （AR-11），{@code hub_outbox} 是二者共用的表而不是共用的类。各写各的插入语句，
 * 共用的是同一张表与同一套投递器，谁也不去引用另一侧的消息类型。
 */
public interface OwnerStatusOutbox {

    /**
     * 写一条待投递的状态变更消息。
     *
     * @param event 事件载荷；{@code eventId} 由 {@link OwnerStatusChangedEvent#eventIdOf} 给出
     */
    void append(OwnerStatusChangedEvent event);
}
