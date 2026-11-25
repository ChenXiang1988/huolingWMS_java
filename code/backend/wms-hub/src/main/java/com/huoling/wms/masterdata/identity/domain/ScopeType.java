package com.huoling.wms.masterdata.identity.domain;

/**
 * 数据域范围类型。指定列表时必须给出具体标识（19）。
 * 闭集外的取值属于参数校验失败，返回 COMMON_9001（契约 API-016）。
 */
public enum ScopeType {
    ALL,
    LIST;

    /** 入口校验用：闭集外抛 COMMON_9001。 */
    public static ScopeType parse(String raw) {
        for (ScopeType value : values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST,
            "字段 scopeType 只接受 ALL 与 LIST，收到 " + raw);
    }
}
