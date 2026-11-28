package com.huoling.wms.masterdata.domain;

/**
 * 货主档案列表的排序参数：契约查询参数 {@code sortBy} 与 {@code sortDir} 解析后的结果。
 *
 * <p>解析放在 kernel：闭集与缺省值只在这里定义一次，运营端的档案列表（API-021）与
 * 货主选择器（API-020）都调用它，非法取值统一按 {@code PARAM_INVALID} 拒绝。
 *
 * <p>缺省为「货主编号升序」（契约 API-020、API-021「sortBy 缺省 ownerNo、sortDir 缺省 asc」）。
 * 游标翻页按这里的字段与方向取锚点：同一组排序参数下翻页不重不漏，换排序键后旧游标不再可用。
 */
public record OwnerListSort(OwnerListSortKey key, OwnerSortDirection direction) {

    public OwnerListSort {
        if (key == null) {
            key = OwnerListSortKey.OWNER_NO;
        }
        if (direction == null) {
            direction = OwnerSortDirection.ASC;
        }
    }

    /**
     * 解析两个查询参数；都缺省时按「货主编号升序」，与契约缺省值一致。
     *
     * @throws OwnerRuleViolation {@code sortBy} 或 {@code sortDir} 不在闭集内时为 {@code PARAM_INVALID}
     */
    public static OwnerListSort parse(String sortBy, String sortDir) {
        return new OwnerListSort(OwnerListSortKey.parse(sortBy), OwnerSortDirection.parse(sortDir));
    }

    /** 是否升序。 */
    public boolean ascending() {
        return direction == OwnerSortDirection.ASC;
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
