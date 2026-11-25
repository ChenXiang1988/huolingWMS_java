package com.huoling.wms.common.archive;

/**
 * 商品档案的七个权限点（本次变更 design「菜单与权限点」，取值逐字一致；21 E-17）。
 *
 * <p>放 common 的原因与错误码相同：Hub 签发权限副本、Site 校验权限副本，
 * 两边必须引用同一份取值；权限点清单以代码为权威源，启动期同步进库（05）。
 */
public final class ArchivePermissionCodes {

    /** 货主端：查询自有档案。 */
    public static final String OWNER_READ = "archive.owner.read";
    /** 货主端：创建、编辑、提交、停用。 */
    public static final String OWNER_WRITE = "archive.owner.write";
    /** 运营端：全量查询。 */
    public static final String HUB_READ = "archive.hub.read";
    /** 运营端：代建草稿。 */
    public static final String HUB_CREATE = "archive.hub.create";
    /** 运营端：审核与批量操作。 */
    public static final String HUB_REVIEW = "archive.hub.review";
    /** 单仓端：本仓查询。 */
    public static final String SITE_READ = "archive.site.read";
    /** 单仓端：发起变更申请。 */
    public static final String SITE_CHANGE = "archive.site.change";

    private ArchivePermissionCodes() {
    }
}
