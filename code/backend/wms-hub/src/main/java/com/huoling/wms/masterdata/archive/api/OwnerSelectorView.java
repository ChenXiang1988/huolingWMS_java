package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.application.OwnerSelectorService;

import java.util.List;

/**
 * 货主选择器条目视图（API-020）：契约字段 {@code ownerId}、{@code ownerNo}、{@code ownerName}。
 *
 * <p>字段名以契约 1.5 为准，不另立同义字段；{@code ownerId} 是十进制字符串，
 * 与全平台的标识约定一致（避免大整数在调用方丢精度）。
 */
public record OwnerSelectorView(String ownerId, String ownerNo, String ownerName) {

    static OwnerSelectorView of(OwnerSelectorService.OwnerOption option) {
        return new OwnerSelectorView(option.ownerId(), option.ownerNo(), option.ownerName());
    }

    /**
     * 选择器列表响应：条目、下一页游标与是否还有下一页。
     *
     * <p>不带 {@code dataTimestamp}：选择器读权威库、不读副本，契约 API-020「响应」明确
     * 「不返回 dataTimestamp」，正常查询不得伪造降级标识（契约 1.1「查询元信息」）。
     * 该字段在信封（{@link OwnerSelectorResponse}）上也不存在——只写在页面对象上不够，
     * 产物里的成功响应 schema 取自信封。
     */
    public record OwnerSelectorPage(List<OwnerSelectorView> items, String nextCursor,
                                    boolean hasMore) {

        static OwnerSelectorPage of(OwnerSelectorService.OwnerPage page) {
            return new OwnerSelectorPage(page.items().stream().map(OwnerSelectorView::of).toList(),
                page.nextCursor(), page.hasMore());
        }
    }
}
