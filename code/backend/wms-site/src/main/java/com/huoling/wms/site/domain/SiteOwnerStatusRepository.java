package com.huoling.wms.site.domain;

import java.util.Optional;

/**
 * 货主状态副本仓储（只读副本 + 一条按版本号单调推进的写入）。
 *
 * <p>接口不提供 {@code delete}：副本只随权威状态前进，不做人工删除；重建副本用脚本重跑，
 * 不用业务代码清表。也不提供按状态批量查询——闸门只按主键取一条。
 */
public interface SiteOwnerStatusRepository {

    /** 按货主标识取副本；本仓还没有该货主的下行消息时返回空。 */
    Optional<SiteOwnerStatus> find(long ownerId);

    /**
     * 应用一条下行状态。
     *
     * <p>按 {@code owner_id} 主键 upsert，且仅当新版本不小于现值时覆盖：
     * 重复投递不产生第二行，乱序到达的旧事件不覆盖新状态（design「消费幂等」）。
     *
     * @return {@code true} 表示本条被应用；{@code false} 表示版本落后被丢弃
     */
    boolean apply(SiteOwnerStatus status);
}
