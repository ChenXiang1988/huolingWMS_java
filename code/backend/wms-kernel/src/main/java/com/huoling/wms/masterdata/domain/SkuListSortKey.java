package com.huoling.wms.masterdata.domain;

/**
 * 列表排序字段闭集，取值即契约字段结构表的 {@code sortBy}：{@code updateTime}、{@code ownerCode}、{@code name}。
 *
 * <p>与 {@link SkuChangeableField} 同一套做法：闭集只在 kernel 定义一次，Hub 与 Site 都来这里解析，
 * 两边各抄一份必然漂移；闭集外取值一律参数错误（契约「枚举约定」：未知值按参数错误拒绝）。
 *
 * <p>同一组取值在两类列表上的含义不同：档案列表按档案字段排序，变更申请列表按申请字段排序
 * （契约字段结构表 {@code sortBy} 一行）。这里只定义闭集本身，落到哪一列由各单元的仓储层决定。
 */
public enum SkuListSortKey {

    /** 更新时间：档案按更新时间，变更申请按申请创建时间。 */
    UPDATE_TIME("updateTime"),
    /** 货主编码：档案按货主编码，变更申请按归属货主。 */
    OWNER_CODE("ownerCode"),
    /** 名称：档案按名称，变更申请按所涉档案字段。 */
    NAME("name");

    private final String wireName;

    SkuListSortKey(String wireName) {
        this.wireName = wireName;
    }

    /** 契约里的英文名，出入参只用它。 */
    public String wireName() {
        return wireName;
    }

    /**
     * 解析 {@code sortBy}；缺省按更新时间（列表默认视图），与 {@link SkuChangeableField} 同一口径：
     * 去掉首尾空白、不区分大小写接受闭集内的名字，其余一律参数错误。
     *
     * @throws SkuRuleViolation 取值不在闭集内时为 {@code PARAM_INVALID}
     */
    public static SkuListSortKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UPDATE_TIME;
        }
        String trimmed = raw.trim();
        for (SkuListSortKey key : values()) {
            if (key.wireName.equalsIgnoreCase(trimmed)) {
                return key;
            }
        }
        throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID,
            "参数 sortBy 取值不合法：收到 " + raw + "，只接受 updateTime、ownerCode、name");
    }
}
