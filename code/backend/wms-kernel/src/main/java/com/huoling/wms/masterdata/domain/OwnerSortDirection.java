package com.huoling.wms.masterdata.domain;

/**
 * 货主档案列表的排序方向闭集（契约 1.1「排序与筛选」、API-020 与 API-021 的 {@code sortDir}）。
 *
 * <p>与商品档案的 {@link SkuSortDirection} 分开定义：两者的缺省方向不同
 * （货主档案缺省 {@code asc}，商品档案缺省 {@code desc}），共用一个枚举会把两处缺省值绑死。
 *
 * <p>闭集外取值一律参数错误，不做「猜一个方向」的兜底（契约 1.1「枚举」）。
 */
public enum OwnerSortDirection {

    /** 升序，货主档案列表的缺省方向。 */
    ASC("asc"),
    /** 降序。 */
    DESC("desc");

    private final String wireName;

    OwnerSortDirection(String wireName) {
        this.wireName = wireName;
    }

    /** 契约里的英文名，出入参只用它。 */
    public String wireName() {
        return wireName;
    }

    /**
     * 解析 {@code sortDir}；缺省为升序（契约 API-020、API-021「缺省 asc」）。
     *
     * @throws OwnerRuleViolation 取值不在闭集内时为 {@code PARAM_INVALID}
     */
    public static OwnerSortDirection parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ASC;
        }
        String trimmed = raw.trim();
        for (OwnerSortDirection direction : values()) {
            if (direction.wireName.equalsIgnoreCase(trimmed)) {
                return direction;
            }
        }
        throw new OwnerRuleViolation(OwnerRuleCodes.PARAM_INVALID,
            "参数 sortDir 取值不合法：收到 " + raw + "，只接受 asc、desc");
    }
}
