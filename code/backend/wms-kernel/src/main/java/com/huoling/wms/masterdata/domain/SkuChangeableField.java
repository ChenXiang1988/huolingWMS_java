package com.huoling.wms.masterdata.domain;

/**
 * 单仓端可以发起变更申请的主数据字段闭集。
 *
 * <p>闭集口径：`26` 3.2 里可由运营审核后生效的字段。归属货主、系统编号、货主系统编码、
 * 基本单位不在此列——它们要么创建后不可变（归属、编号），要么影响库存计量与货主内判重口径，
 * 变更必须走运营改档而不是单仓申请。库位关联字段不属档案（本次变更 design「强制手段分层」）。
 *
 * <p>取值形态即枚举名（闭集外按 {@code PARAM_INVALID} 拒绝）。
 */
public enum SkuChangeableField {

    NAME,
    BARCODE,
    STORAGE_CONDITION,
    CATEGORY,
    SPECIFICATION,
    BRAND,
    PACKAGE_SPEC,
    PACKAGE_CONVERSION_RATE,
    NET_WEIGHT,
    GROSS_WEIGHT,
    VOLUME,
    REMARK;

    /** 目标值是否按定点数解析（禁止浮点）。 */
    public boolean numeric() {
        return switch (this) {
            case PACKAGE_CONVERSION_RATE, NET_WEIGHT, GROSS_WEIGHT, VOLUME -> true;
            default -> false;
        };
    }

    /** 闭集解析：不区分大小写接受枚举名，其余取值一律参数错误。 */
    public static SkuChangeableField parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "缺少变更字段 field");
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "变更字段 " + raw + " 不在允许集合内");
        }
    }
}
