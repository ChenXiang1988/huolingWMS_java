package com.huoling.wms.masterdata.identity.domain;

import java.util.Objects;

/**
 * 授权动作的三条硬约束：不能自授、至少保留一个可用权限管理员、角色端标识必须匹配（05、19）。
 */
public final class AuthorizationRules {

    private AuthorizationRules() {
    }

    /** 操作者不得修改自己的角色、权限点或数据域。 */
    public static void assertNotSelf(Long operatorAccountId, Long targetAccountId) {
        if (operatorAccountId != null && Objects.equals(operatorAccountId, targetAccountId)) {
            throw new IdentityException(IdentityErrorCodes.AUTHZ_SELF_OPERATION, "不能给自己授权");
        }
    }

    /** 变更后可用权限管理员数量为零时必须拒绝。 */
    public static void assertPermissionAdminRemains(long remainingAdmins) {
        if (remainingAdmins <= 0) {
            throw new IdentityException(IdentityErrorCodes.AUTHZ_LAST_ADMIN,
                "至少保留一个可用的权限管理员");
        }
    }

    /** 角色端标识与账号所在端不一致时拒绝。 */
    public static void assertClientMatches(ClientType roleClient, ClientType accountClient) {
        if (roleClient != accountClient) {
            throw new IdentityException(IdentityErrorCodes.ROLE_CLIENT_MISMATCH,
                "角色端标识与账号不匹配");
        }
    }
}
