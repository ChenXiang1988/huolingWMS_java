package com.huoling.wms.masterdata.identity.domain;

/**
 * 身份与权限的错误码常量，与契约章错误码表逐条对应。
 */
public final class IdentityErrorCodes {

    public static final String AUTH_BAD_CREDENTIALS = "AUTH_1001";
    public static final String AUTH_TOKEN_INVALID = "AUTH_1002";
    public static final String AUTH_ACCOUNT_DISABLED = "AUTH_1003";
    public static final String AUTH_REFRESH_INVALID = "AUTH_1004";

    public static final String AUTHZ_NO_PERMISSION = "AUTHZ_2001";
    public static final String AUTHZ_EMPTY_SCOPE = "AUTHZ_2002";
    public static final String AUTHZ_SCOPE_EXCEEDED = "AUTHZ_2003";
    public static final String AUTHZ_SELF_OPERATION = "AUTHZ_2004";
    public static final String AUTHZ_LAST_ADMIN = "AUTHZ_2005";

    public static final String ACCOUNT_NAME_TAKEN = "ACCOUNT_3001";
    public static final String ACCOUNT_EMPLOYEE_TAKEN = "ACCOUNT_3002";
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_3003";

    public static final String ROLE_NOT_FOUND = "ROLE_4001";
    public static final String ROLE_CLIENT_MISMATCH = "ROLE_4002";

    public static final String PERM_UNKNOWN = "PERM_5001";

    public static final String SCOPE_DIMENSION_INVALID = "SCOPE_6001";
    public static final String SCOPE_TARGET_INVALID = "SCOPE_6002";

    public static final String COMMON_BAD_REQUEST = "COMMON_9001";
    public static final String COMMON_RATE_LIMITED = "COMMON_9002";
    public static final String COMMON_UNAVAILABLE = "COMMON_9003";
    public static final String COMMON_VERSION_CONFLICT = "COMMON_9004";
    /** 同一幂等键已受理过，但本次请求体指纹与首次不同（契约错误码表 COMMON_9005）。 */
    public static final String COMMON_IDEMPOTENCY_CONFLICT = "COMMON_9005";

    private IdentityErrorCodes() {
    }
}
