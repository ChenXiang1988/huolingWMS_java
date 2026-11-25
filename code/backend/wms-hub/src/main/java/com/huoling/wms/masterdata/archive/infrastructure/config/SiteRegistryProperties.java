package com.huoling.wms.masterdata.archive.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 站点登记（API-018、API-019 的凭证来源）。
 *
 * <p><b>落点说明（偏差已登记）</b>：仓库档案与站点凭证的权威表不在本次变更的迁移脚本里，
 * 当前有效版本也没有仓库档案表，因此站点编码、凭证、本仓标识与权限副本由部署配置提供，
 * 由云上密钥服务注入凭证，仓库内只留占位符（01「仓库内不存环境密钥」）。
 *
 * <p>{@code site-ids} 声明的是本仓标识集合：必须恰好一个，声明多个即「多仓声明」，
 * 按契约返回 {@code FORBIDDEN_SITE}，不签发令牌。
 */
@ConfigurationProperties(prefix = "wms.archive.site-registry")
public class SiteRegistryProperties {

    private List<Site> sites = new ArrayList<>();

    public List<Site> getSites() {
        return sites;
    }

    public void setSites(List<Site> sites) {
        this.sites = sites == null ? new ArrayList<>() : sites;
    }

    /** 一个站点登记项。 */
    public static class Site {

        /** 站点编码，API-018 的 {@code siteCode}。 */
        private String code;
        /** 站点凭证，线上由密钥服务注入。 */
        private String credential;
        /** 本仓标识集合，必须恰好一个。 */
        private List<String> siteIds = new ArrayList<>();
        /** 令牌主体账号标识，缺省为零（系统签发）。 */
        private Long accountId;
        /** 权限副本：随令牌下行给 Site 单元的功能权限点。 */
        private List<String> permissions = new ArrayList<>();

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getCredential() {
            return credential;
        }

        public void setCredential(String credential) {
            this.credential = credential;
        }

        public List<String> getSiteIds() {
            return siteIds;
        }

        public void setSiteIds(List<String> siteIds) {
            this.siteIds = siteIds == null ? new ArrayList<>() : siteIds;
        }

        public Long getAccountId() {
            return accountId;
        }

        public void setAccountId(Long accountId) {
            this.accountId = accountId;
        }

        public List<String> getPermissions() {
            return permissions;
        }

        public void setPermissions(List<String> permissions) {
            this.permissions = permissions == null ? new ArrayList<>() : permissions;
        }
    }
}
