package com.huoling.wms.common.owner;

/**
 * 货主档案的两枚权限点（本次变更契约 E-20，取值逐字一致）。
 *
 * <p>只有两枚：读（查询、详情、受理结果查询、选择器）与写（建档、改档、停用、启用）。
 * 放 common 的原因与商品档案权限点相同：Hub 签发权限副本、Site 校验权限副本、初始化脚本绑定角色，
 * 三处必须引用同一份取值，权限点清单以代码为权威源（`05`、`19`）。
 */
public final class OwnerPermissionCodes {

    /** 运营端：查询货主档案、查询受理结果、检索可用货主（API-020 起）。 */
    public static final String READ = "owner.hub.read";
    /** 运营端：建档、改档、停用、重新启用。 */
    public static final String WRITE = "owner.hub.write";

    private OwnerPermissionCodes() {
    }
}
