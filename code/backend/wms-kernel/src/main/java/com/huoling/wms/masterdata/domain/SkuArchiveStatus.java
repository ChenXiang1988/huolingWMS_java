package com.huoling.wms.masterdata.domain;

/**
 * 商品档案状态（本次变更 design「领域模型与状态机」；取值与契约「枚举约定」逐字一致）。
 *
 * <p>三态之外新增待审核与驳回：审核通过是进入启用的动作，不另立「已通过」持久态。
 * 停用经提交恢复时先回到待审核，不直接回启用。
 */
public enum SkuArchiveStatus {

    /** 草稿：未提交，不进业务链路。 */
    DRAFT,
    /** 待审核：已提交，等待运营审核。 */
    PENDING,
    /** 启用：已生效，可被单据与库存引用。 */
    ACTIVE,
    /** 驳回：审核未通过，保留原因，可编辑后重提。 */
    REJECTED,
    /** 停用：不接新单，存量可走完。 */
    DISABLED
}
