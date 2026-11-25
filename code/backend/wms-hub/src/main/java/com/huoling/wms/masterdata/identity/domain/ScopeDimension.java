package com.huoling.wms.masterdata.identity.domain;

/**
 * 数据域维度。只允许货主与仓两个维度，白名单外一律拒绝（19）。
 * 闭集外的取值属于参数校验失败，返回 SCOPE_6001（契约 API-016）。
 */
public enum ScopeDimension {
    OWNER,
    WAREHOUSE;

    /** 入口校验用：闭集外抛 SCOPE_6001，不到库表 CHECK 才失败。 */
    public static ScopeDimension parse(String raw) {
        for (ScopeDimension value : values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        throw new IdentityException(IdentityErrorCodes.SCOPE_DIMENSION_INVALID,
            "字段 dimension 只接受 OWNER 与 WAREHOUSE，收到 " + raw);
    }
}
