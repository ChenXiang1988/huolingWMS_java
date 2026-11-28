package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.common.owner.OwnerPermissionCodes;

/**
 * 货主档案权限点在 Hub 侧的别名，取值只有一处定义：{@link OwnerPermissionCodes}（common）。
 *
 * <p>沿用档案模块的做法：常量放 common（Site 与 Hub 都要引用同一份），业务包只做别名，
 * 不另抄字符串。
 */
public final class OwnerPermissionAlias {

    /** 运营端：查询货主档案、查询受理结果、检索可用货主。 */
    public static final String READ = OwnerPermissionCodes.READ;
    /** 运营端：建档、改档、停用、重新启用。 */
    public static final String WRITE = OwnerPermissionCodes.WRITE;

    private OwnerPermissionAlias() {
    }
}
