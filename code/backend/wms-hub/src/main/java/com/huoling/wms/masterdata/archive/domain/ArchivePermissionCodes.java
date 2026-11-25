package com.huoling.wms.masterdata.archive.domain;

/**
 * 三端商品档案权限点（本次变更 design「菜单与权限点」，取值逐字一致）。
 *
 * <p>取值只有一处定义：{@code com.huoling.wms.common.archive.ArchivePermissionCodes}（common），
 * Hub 与 Site 共用同一份；
 * 权限点写在代码里，启动期由 {@code PermissionStartupSync} 同步进表（05「权限点写在代码里」）。
 * 数据域由仓储层注入，两层都在服务端，前端只隐藏入口。
 */
public final class ArchivePermissionCodes {

    /** 货主端：查询自有档案。 */
    public static final String OWNER_READ = com.huoling.wms.common.archive.ArchivePermissionCodes.OWNER_READ;
    /** 货主端：创建、编辑、提交、停用。 */
    public static final String OWNER_WRITE = com.huoling.wms.common.archive.ArchivePermissionCodes.OWNER_WRITE;
    /** 运营端：全量查询。 */
    public static final String HUB_READ = com.huoling.wms.common.archive.ArchivePermissionCodes.HUB_READ;
    /** 运营端：代建草稿。 */
    public static final String HUB_CREATE = com.huoling.wms.common.archive.ArchivePermissionCodes.HUB_CREATE;
    /** 运营端：审核与批量操作。 */
    public static final String HUB_REVIEW = com.huoling.wms.common.archive.ArchivePermissionCodes.HUB_REVIEW;
    /** 单仓端：本仓查询。 */
    public static final String SITE_READ = com.huoling.wms.common.archive.ArchivePermissionCodes.SITE_READ;
    /** 单仓端：发起变更申请。 */
    public static final String SITE_CHANGE = com.huoling.wms.common.archive.ArchivePermissionCodes.SITE_CHANGE;

    private ArchivePermissionCodes() {
    }
}
