package com.huoling.wms.masterdata.domain;

/**
 * 货主档案状态（`21` E-18、契约 1.5）：只有「可用」与「停用」两值。
 *
 * <p>两态互斥且穷尽：是否停用只由这一列表达，不另设停用标记；MUST NOT 用它表达合同是否生效
 * ——「无生效合同不可作业」由计费域判定（D33），货主档案照旧显示可用。
 */
public enum OwnerStatus {

    /** 可用：全仓网可见，可被选择器返回、可接受以该货主为对象的新建业务。 */
    ACTIVE,
    /** 停用：阻断以该货主为对象的新建业务，不阻断存量单据与任务。 */
    DISABLED;

    /** 是否可用；选择器与受理闸门都以这一判定为准。 */
    public boolean active() {
        return this == ACTIVE;
    }
}
