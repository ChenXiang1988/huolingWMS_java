package com.huoling.wms.masterdata.domain;

/**
 * 货主档案的状态变更动作（`21` E-19、契约 1.5 的 {@code lastStatusChange.action}）。
 *
 * <p>三个动作各自只对应一条合法迁移：{@code CREATE} 建档、{@code DISABLE} 停用、{@code ENABLE} 启用；
 * {@code EDIT} 是唯一不改状态的写动作，它不是状态变更动作，只参与状态守卫（停用态必须拒绝）。
 * 系统自动恢复可用的路径不存在，所以没有第四个「自动」动作（OI-12）。
 */
public enum OwnerAction {

    /** 建档：无档案 → 可用。 */
    CREATE,
    /** 改档：整档替换四项，状态不变。 */
    EDIT,
    /** 停用：可用 → 停用，必须填写原因。 */
    DISABLE,
    /** 重新启用：停用 → 可用，必须填写原因，只能由人发起。 */
    ENABLE
}
