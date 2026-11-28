package com.huoling.wms.masterdata.archive.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由键按 {@code event_type} 前缀解析（tasks 1.13；design「事件与投递」表）。
 *
 * <p>两类事件共用一条交换，解析错就是「货主停用下发给商品档案队列」——单仓端永远收不到状态，
 * 表现是「停用了还能开单」，所以这里把四个入口都钉死：SKU 前缀、OWNER 前缀、契约事件名的大小写、
 * 未知类型。
 */
class OutboxRoutingTest {

    private static final String SKU_KEY = "wms.archive.sku";
    private static final String OWNER_KEY = "wms.owner.status";

    @Test
    @DisplayName("商品档案事件走既有路由键，行为不变")
    void 商品档案事件() {
        assertThat(OutboxRouting.routingKey("SKU_ARCHIVE_ACTIVATED", SKU_KEY, OWNER_KEY))
            .isEqualTo(SKU_KEY);
        assertThat(OutboxRouting.routingKey("SKU_CHANGE_REQUEST_REVIEWED", SKU_KEY, OWNER_KEY))
            .isEqualTo(SKU_KEY);
        assertThat(OutboxRouting.isOwnerEvent("SKU_ARCHIVE_CHANGED")).isFalse();
    }

    @Test
    @DisplayName("货主状态事件走 wms.owner.status，事件名大小写不影响判定")
    void 货主状态事件() {
        // 契约 1.9 的代码常量是小写事件名，设计章的前缀形态是大写，两者必须落到同一分支。
        assertThat(OutboxRouting.routingKey("owner.status.changed", SKU_KEY, OWNER_KEY))
            .isEqualTo(OWNER_KEY);
        assertThat(OutboxRouting.routingKey("OWNER_STATUS_CHANGED", SKU_KEY, OWNER_KEY))
            .isEqualTo(OWNER_KEY);
        assertThat(OutboxRouting.isOwnerEvent("owner.status.changed")).isTrue();
    }

    @Test
    @DisplayName("未知或缺失类型回落既有路由键，不被误判成货主事件")
    void 未知类型回落() {
        assertThat(OutboxRouting.routingKey("SOMETHING_ELSE", SKU_KEY, OWNER_KEY)).isEqualTo(SKU_KEY);
        assertThat(OutboxRouting.routingKey(null, SKU_KEY, OWNER_KEY)).isEqualTo(SKU_KEY);
        assertThat(OutboxRouting.isOwnerEvent(null)).isFalse();
    }
}
