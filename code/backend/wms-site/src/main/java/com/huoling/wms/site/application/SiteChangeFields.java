package com.huoling.wms.site.application;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.domain.SiteException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 契约 {@code changeField} 闭集（与 Hub 侧同一份口径）。
 *
 * <p>闭集取自契约「字段结构与枚举」：{@code name}、{@code spec}、{@code category}、
 * {@code storageCondition}；{@code baseUnit} 不在 Kernel 的允许集合内（基本单位影响库存计量与判重口径，
 * 必须走运营改档），因此在这里按参数错误拒绝，不落半成品分支（已登记为契约缺口）。
 *
 * <p>闭集只此一处：接口入口（{@code SiteChangeRequestController}）与申请应用服务都调这里，
 * 不在控制器里再写一份字段清单。
 */
public final class SiteChangeFields {

    private static final Map<String, String> ALLOWED = allowed();

    private SiteChangeFields() {
    }

    /**
     * 按契约闭集校验并归一变更字段。
     *
     * @throws SiteException 缺失、闭集外取值或 Kernel 不接受单仓变更的字段为 {@code PARAM_INVALID}
     */
    public static String require(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID, "缺少变更字段 changeField");
        }
        String key = raw.trim();
        if ("baseUnit".equals(key)) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "基本单位不接受单仓变更申请，请由运营改档");
        }
        if (!ALLOWED.containsKey(key)) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "变更字段 " + raw + " 不在允许集合内，只接受 name、spec、category、storageCondition");
        }
        return key;
    }

    private static Map<String, String> allowed() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", "name");
        map.put("spec", "spec");
        map.put("category", "category");
        map.put("storageCondition", "storageCondition");
        return Map.copyOf(map);
    }
}
