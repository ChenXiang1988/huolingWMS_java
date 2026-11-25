package com.huoling.wms.site.api;

import java.util.Set;

/**
 * 单仓端会话：令牌里的唯一 {@code siteId} 与权限副本（契约「单仓端会话与权限」）。
 *
 * <p>数据域只有一个来源——令牌声明；请求参数里出现的 {@code siteId} 一律忽略，
 * 不允许覆盖会话域（05「请求参数里出现货主或仓的标识时，忽略或校验」）。
 */
public record SiteSession(Long siteId, Long accountId, Set<String> permissions) {

    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }
}
