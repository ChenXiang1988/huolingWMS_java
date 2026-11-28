package com.huoling.wms.masterdata.archive.domain;

import java.util.Locale;

/**
 * 本地消息的路由键解析（design「停用货主如何阻断单仓端新建业务」的「事件与投递」表）。
 *
 * <p>一条交换（{@code wms.archive.events}）承载两类下行：商品档案与货主状态。路由键由
 * <b>{@code event_type} 前缀</b>决定，而不是由调用方各自指定——生产者只管写消息，
 * 加一类事件不需要改投递器以外的任何地方：
 *
 * <ul>
 *   <li>{@code SKU_*} → {@code wms.archive.sku}（既有商品档案下行，行为不变）</li>
 *   <li>{@code OWNER_*} → {@code wms.owner.status}（本次新增的货主状态下行）</li>
 * </ul>
 *
 * <p>事件名的代码常量是 {@code owner.status.changed}（契约 1.9），前缀判定因此<b>不区分大小写</b>：
 * 契约写的是事件名，设计章写的是前缀形态，两者指的是同一个常量，不该因为大小写产生第三个分支。
 *
 * <p>未知前缀回落到商品档案路由键：既有生产者只有上述两类，回落只影响未来新增的类型，
 * 让它至少不会静默丢失，并且调用方会在投递日志里看到未识别的类型。
 */
public final class OutboxRouting {

    /** 货主状态事件的事件类型前缀（判定时统一转大写）。 */
    private static final String OWNER_PREFIX = "OWNER";

    private OutboxRouting() {
    }

    /** 是否为货主状态事件。 */
    public static boolean isOwnerEvent(String eventType) {
        return eventType != null
            && eventType.toUpperCase(Locale.ROOT).startsWith(OWNER_PREFIX);
    }

    /**
     * 按事件类型取路由键。
     *
     * @param skuRoutingKey   商品档案路由键（部署配置，缺省 {@code wms.archive.sku}）
     * @param ownerRoutingKey 货主状态路由键（部署配置，缺省 {@code wms.owner.status}）
     */
    public static String routingKey(String eventType, String skuRoutingKey, String ownerRoutingKey) {
        return isOwnerEvent(eventType) ? ownerRoutingKey : skuRoutingKey;
    }
}
