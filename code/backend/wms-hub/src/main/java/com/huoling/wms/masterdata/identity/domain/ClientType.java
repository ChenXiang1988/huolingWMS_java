package com.huoling.wms.masterdata.identity.domain;

/**
 * 端标识。角色带端标识，登录某端时只加载该端角色，不跨端合并（05）。
 */
public enum ClientType {
    HUB,
    SITE,
    MOBILE,
    OWNER
}
