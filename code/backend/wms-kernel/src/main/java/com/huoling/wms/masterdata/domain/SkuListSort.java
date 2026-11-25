package com.huoling.wms.masterdata.domain;

/**
 * 列表排序参数：契约查询参数 {@code sortBy} 与 {@code sortDir} 解析后的结果。
 *
 * <p>解析放在 kernel：闭集与缺省值只在这里定义一次，Hub 与 Site 的列表接口都调用它，
 * 非法取值统一按 {@code PARAM_INVALID} 拒绝（契约字段结构表、错误码表第一行）。
 *
 * <p>游标翻页按这里的字段与方向取锚点：同一组排序参数下翻页不重不漏，
 * 换排序键后旧游标不再可用（各单元游标编码里带着字段与方向）。
 */
public record SkuListSort(SkuListSortKey key, SkuSortDirection direction) {

    public SkuListSort {
        if (key == null) {
            key = SkuListSortKey.UPDATE_TIME;
        }
        if (direction == null) {
            direction = SkuSortDirection.DESC;
        }
    }

    /**
     * 解析两个查询参数；都缺省时按「更新时间降序」，与列表默认视图一致。
     *
     * @throws SkuRuleViolation {@code sortBy} 或 {@code sortDir} 不在闭集内时为 {@code PARAM_INVALID}
     */
    public static SkuListSort parse(String sortBy, String sortDir) {
        return new SkuListSort(SkuListSortKey.parse(sortBy), SkuSortDirection.parse(sortDir));
    }

    /** 是否升序。 */
    public boolean ascending() {
        return direction == SkuSortDirection.ASC;
    }

    /** 落在 SQL 与游标里的字段名，取枚举名而不是契约英文名，避免与列值混淆。 */
    public String keyName() {
        return key.name();
    }

    /** 落在 SQL 与游标里的方向名。 */
    public String directionName() {
        return direction.name();
    }
}
