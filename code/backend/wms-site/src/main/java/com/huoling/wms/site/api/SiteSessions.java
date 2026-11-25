package com.huoling.wms.site.api;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.domain.SiteException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求属性取会话并校验权限点。
 *
 * <p>令牌校验与数据域解析在 {@link SiteTokenFilter} 完成，控制器只做功能权限这一层：
 * 功能权限决定「能不能做」，数据域决定「能看谁的数据」，两者缺一不可（05「权限按功能加数据域双重校验」）。
 * 权限副本来自令牌（Hub 下行），缺少权限点按 {@code FORBIDDEN_SITE} 拒绝。
 */
final class SiteSessions {

    private SiteSessions() {
    }

    static SiteSession require(HttpServletRequest request) {
        Object session = request.getAttribute(SiteTokenFilter.SESSION_ATTRIBUTE);
        if (!(session instanceof SiteSession siteSession)) {
            throw new SiteException(ArchiveContractCodes.FORBIDDEN_SITE, "缺少单仓端会话，拒绝访问");
        }
        return siteSession;
    }

    static SiteSession require(HttpServletRequest request, String permissionCode) {
        SiteSession session = require(request);
        if (!session.hasPermission(permissionCode)) {
            throw new SiteException(ArchiveContractCodes.FORBIDDEN_SITE,
                "缺少权限点 " + permissionCode);
        }
        return session;
    }
}
