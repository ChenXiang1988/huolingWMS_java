package com.huoling.wms.masterdata.domain;

/**
 * 列表排序方向闭集，取值即契约字段结构表的 {@code sortDir}：{@code asc}、{@code desc}，缺省 {@code desc}。
 *
 * <p>闭集外取值一律参数错误，不做「猜一个方向」的兜底（契约「枚举约定」）。
 */
public enum SkuSortDirection {

    /** 升序。 */
    ASC("asc"),
    /** 降序，缺省方向。 */
    DESC("desc");

    private final String wireName;

    SkuSortDirection(String wireName) {
        this.wireName = wireName;
    }

    /** 契约里的英文名，出入参只用它。 */
    public String wireName() {
        return wireName;
    }

    /**
     * 解析 {@code sortDir}；缺省为降序（契约：缺省 {@code desc}），与 {@link SkuChangeableField} 同一口径：
     * 去掉首尾空白、不区分大小写接受闭集内的名字，其余一律参数错误。
     *
     * @throws SkuRuleViolation 取值不在闭集内时为 {@code PARAM_INVALID}
     */
    public static SkuSortDirection parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DESC;
        }
        String trimmed = raw.trim();
        for (SkuSortDirection direction : values()) {
            if (direction.wireName.equalsIgnoreCase(trimmed)) {
                return direction;
            }
        }
        throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID,
            "参数 sortDir 取值不合法：收到 " + raw + "，只接受 asc、desc");
    }
}
