package com.huoling.wms.masterdata.domain;

/**
 * 货主档案列表的排序字段闭集（契约 1.1、API-020 与 API-021 的 {@code sortBy}）。
 *
 * <p>白名单只有三项：{@code ownerNo}、{@code ownerName}、{@code updateTime}，缺省 {@code ownerNo}。
 * 闭集只在 kernel 定义一次，Hub 的列表与选择器都来这里解析；白名单外取值一律参数错误，
 * 不静默回落到缺省排序（契约 1.1）。
 *
 * <p>多字段排序不提供：键集分页需要稳定单键，第二排序键固定为货主编号
 * （编号唯一，翻页不重不漏）。
 */
public enum OwnerListSortKey {

    /** 货主编号，缺省排序键。 */
    OWNER_NO("ownerNo"),
    /** 货主名称。 */
    OWNER_NAME("ownerName"),
    /** 最后修改时间。 */
    UPDATE_TIME("updateTime");

    private final String wireName;

    OwnerListSortKey(String wireName) {
        this.wireName = wireName;
    }

    /** 契约里的英文名，出入参只用它。 */
    public String wireName() {
        return wireName;
    }

    /**
     * 解析 {@code sortBy}；缺省按货主编号升序（契约「缺省 ownerNo」）。
     *
     * @throws OwnerRuleViolation 取值不在白名单内时为 {@code PARAM_INVALID}
     */
    public static OwnerListSortKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OWNER_NO;
        }
        String trimmed = raw.trim();
        for (OwnerListSortKey key : values()) {
            if (key.wireName.equalsIgnoreCase(trimmed)) {
                return key;
            }
        }
        throw new OwnerRuleViolation(OwnerRuleCodes.PARAM_INVALID,
            "参数 sortBy 取值不合法：收到 " + raw + "，只接受 ownerNo、ownerName、updateTime");
    }
}
