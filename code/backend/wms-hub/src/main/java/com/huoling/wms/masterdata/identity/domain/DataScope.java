package com.huoling.wms.masterdata.identity.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 合并后的数据域。只含货主与仓两个维度，每个维度要么全部、要么指定列表。
 * 未配置的维度按全部处理：运营端角色默认全域，不因缺少配置而无法作业（05）。
 */
public record DataScope(boolean allOwners, Set<Long> ownerIds,
                        boolean allWarehouses, Set<Long> warehouseIds) {

    public DataScope {
        ownerIds = Collections.unmodifiableSet(new LinkedHashSet<>(ownerIds));
        warehouseIds = Collections.unmodifiableSet(new LinkedHashSet<>(warehouseIds));
    }

    public static DataScope all() {
        return new DataScope(true, Set.of(), true, Set.of());
    }

    /** 任一维度为指定列表且为空时视为空域，必须拒绝（19）。 */
    public boolean isEmpty() {
        return (!allOwners && ownerIds.isEmpty()) || (!allWarehouses && warehouseIds.isEmpty());
    }

    public boolean coversOwner(Long ownerId) {
        return allOwners || ownerIds.contains(ownerId);
    }

    public boolean coversWarehouse(Long warehouseId) {
        return allWarehouses || warehouseIds.contains(warehouseId);
    }
}
