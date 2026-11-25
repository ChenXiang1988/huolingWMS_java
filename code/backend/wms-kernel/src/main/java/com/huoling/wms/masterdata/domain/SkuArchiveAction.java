package com.huoling.wms.masterdata.domain;

/**
 * 商品档案上允许的动作（本次变更 design「领域模型与状态机」）。
 *
 * <p>动作是状态迁移的输入，状态本身只能是 {@link SkuArchiveStatus} 的取值；
 * 迁移是否合法由 {@link SkuArchiveStateMachine} 判定，Hub 与 Site 不得各写一套判断。
 */
public enum SkuArchiveAction {

    /** 提交审核：草稿、驳回、停用三态均可提交（停用是「提交恢复」）。 */
    SUBMIT,
    /** 审核通过：待审核到启用。 */
    APPROVE,
    /** 驳回：待审核到驳回，必须给出原因。 */
    REJECT,
    /** 编辑：只在草稿与驳回态允许，不改变状态。 */
    EDIT,
    /** 停用：启用到停用。 */
    DISABLE
}
