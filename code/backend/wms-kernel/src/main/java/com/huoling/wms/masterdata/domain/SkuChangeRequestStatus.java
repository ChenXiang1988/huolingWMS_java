package com.huoling.wms.masterdata.domain;

/**
 * 变更申请状态（本次变更 design「领域模型与状态机」：申请经运营审核通过后生效，驳回保留原因）。
 */
public enum SkuChangeRequestStatus {

    /** 待审核：已提交，原档案内容不变。 */
    PENDING,
    /** 已通过：写回权威档案并递增版本号。 */
    APPROVED,
    /** 已驳回：保留原因。 */
    REJECTED
}
