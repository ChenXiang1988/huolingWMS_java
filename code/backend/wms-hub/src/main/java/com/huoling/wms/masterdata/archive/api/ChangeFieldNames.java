package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.domain.SkuChangeableField;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 契约 {@code changeField} 与 Kernel 变更字段枚举之间的翻译层。
 *
 * <p>契约「字段结构与枚举」把线上闭集写成分页式的四个小驼峰名：{@code name}、{@code spec}、
 * {@code category}、{@code storageCondition}（另一项 {@code baseUnit} 见下）。Kernel 的
 * {@link SkuChangeableField} 用枚举名表达同一批字段，两者只在这一处对接，别处不各写一套。
 *
 * <p><b>契约缺口（已登记）</b>：契约闭集里的 {@code baseUnit}（基本单位）在 Kernel 中不属于可变更字段
 * ——`26` 3.2 与 {@code SkuChangeableField} 明确基本单位影响库存计量与货主内判重口径，
 * 变更必须走运营改档。此处按 Kernel 拒绝，待裁决后再决定是否放开。
 */
final class ChangeFieldNames {

    private static final Map<String, SkuChangeableField> WIRE_TO_FIELD = wireToField();

    private ChangeFieldNames() {
    }

    /**
     * 按契约闭集解析变更字段。
     *
     * @throws ArchiveException 闭集外取值或 Kernel 不接受单仓变更的字段为 {@code PARAM_INVALID}
     */
    static SkuChangeableField parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "缺少变更字段 changeField");
        }
        String key = raw.trim();
        if ("baseUnit".equals(key)) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "基本单位不接受单仓变更申请，请由运营改档");
        }
        SkuChangeableField field = WIRE_TO_FIELD.get(key);
        if (field == null) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "变更字段 " + raw + " 不在允许集合内，只接受 name、spec、category、storageCondition");
        }
        return field;
    }

    /** 枚举反解成契约字段名，用于申请列表回显。 */
    static String wire(SkuChangeableField field) {
        for (Map.Entry<String, SkuChangeableField> entry : WIRE_TO_FIELD.entrySet()) {
            if (entry.getValue() == field) {
                return entry.getKey();
            }
        }
        return field.name();
    }

    private static Map<String, SkuChangeableField> wireToField() {
        Map<String, SkuChangeableField> map = new LinkedHashMap<>();
        map.put("name", SkuChangeableField.NAME);
        map.put("spec", SkuChangeableField.SPECIFICATION);
        map.put("category", SkuChangeableField.CATEGORY);
        map.put("storageCondition", SkuChangeableField.STORAGE_CONDITION);
        return Map.copyOf(map);
    }
}
