package com.huoling.wms.masterdata.archive.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huoling.wms.masterdata.archive.application.OwnerSelectorService;

import java.time.Instant;
import java.util.List;

/**
 * 货主选择器条目视图（API-020）：只有契约字段 {@code ownerId} 与 {@code ownerName}。
 *
 * <p>展示名的英文名以契约「字段结构与枚举」为准，是 {@code ownerName}；
 * 不另立同义字段，页面与服务端一律按这一名读。
 */
public record OwnerSelectorView(String ownerId, String ownerName) {

    static OwnerSelectorView of(OwnerSelectorService.OwnerOption option) {
        return new OwnerSelectorView(option.ownerId(), option.ownerName());
    }

    /**
     * 选择器列表响应：条目、下一页游标、是否还有下一页。
     *
     * <p>{@code dataTimestamp} 只在查询降级时出现（契约 API-020「响应」「降级」两行）：正常查询为
     * {@code null} 并从响应里省略；{@code nextCursor} 与 {@code hasMore} 是列表响应必备字段，
     * 不参与「空值不输出」，因此省略规则只落在 {@code dataTimestamp} 这一个字段上。
     */
    public record OwnerSelectorPage(List<OwnerSelectorView> items, String nextCursor,
                                    boolean hasMore,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    Instant dataTimestamp) {

        static OwnerSelectorPage of(OwnerSelectorService.OwnerPage page) {
            return new OwnerSelectorPage(page.items().stream().map(OwnerSelectorView::of).toList(),
                page.nextCursor(), page.hasMore(), page.dataTimestamp());
        }
    }
}
