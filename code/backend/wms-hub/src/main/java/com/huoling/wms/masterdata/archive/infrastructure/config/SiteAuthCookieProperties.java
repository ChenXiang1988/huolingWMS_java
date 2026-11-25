package com.huoling.wms.masterdata.archive.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 单仓端刷新凭证 Cookie 的跨源属性（本次变更 tasks 1.18；契约 API-018「刷新凭证用 httpOnly cookie 下发」）。
 *
 * <p>为什么需要配置：单仓端与 Hub 常常不同源（单仓部署在自己的域名下）。同站部署时
 * {@code SameSite=Lax} 够用；跨源部署时浏览器只接受 {@code SameSite=None} 且必须带 {@code Secure}
 * 的 Cookie，默认值不改会在跨源场景下静默丢 Cookie，表现为「续期总是 401」。
 *
 * <p>默认值刻意保守（{@code Lax} 且不加 {@code Secure}）：本地与同站部署不改配置即可用，
 * 跨源部署由环境变量设置 {@code WMS_ARCHIVE_SITE_AUTH_REFRESH_COOKIE_SAME_SITE=None}
 * 与 {@code WMS_ARCHIVE_SITE_AUTH_REFRESH_COOKIE_SECURE=true}，不用改代码。
 *
 * <p>即使浏览器仍拦截第三方 Cookie，续期也不会断：契约允许 API-019 在请求体里显式回传
 * {@code refreshCredential}，服务端两条通道都收（见 {@code SiteAuthController}）。
 */
@ConfigurationProperties(prefix = "wms.archive.site-auth")
public class SiteAuthCookieProperties {

    /** 刷新凭证 Cookie 的 {@code SameSite} 取值：{@code Lax}、{@code Strict} 或 {@code None}。 */
    private String refreshCookieSameSite = "Lax";

    /** 刷新凭证 Cookie 是否带 {@code Secure}；{@code SameSite=None} 时无论如何都按安全 Cookie 下发。 */
    private boolean refreshCookieSecure = false;

    public String getRefreshCookieSameSite() {
        return refreshCookieSameSite;
    }

    public void setRefreshCookieSameSite(String refreshCookieSameSite) {
        this.refreshCookieSameSite = refreshCookieSameSite == null || refreshCookieSameSite.isBlank()
            ? "Lax" : refreshCookieSameSite;
    }

    public boolean isRefreshCookieSecure() {
        return refreshCookieSecure;
    }

    public void setRefreshCookieSecure(boolean refreshCookieSecure) {
        this.refreshCookieSecure = refreshCookieSecure;
    }
}
