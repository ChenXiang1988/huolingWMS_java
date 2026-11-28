package com.huoling.wms.masterdata.acceptance.domain;

/**
 * 受理状态（契约「字段结构与枚举」的 {@code acceptStatus}）。
 *
 * <p>三态互斥：{@code PROCESSING} 处理中，{@code SUCCEEDED} 成功，{@code FAILED} 失败并带错误码。
 * 只由受理服务按执行结果推进，写接口本身不返回执行结果。
 *
 * <p>本包由商品档案与货主档案两个业务共用（本次变更 design「幂等与异步受理」）：
 * 机制只有一份，业务各自写自己的 Command。
 */
public enum AcceptanceStatus {

    /** 已受理，异步执行尚未结束。 */
    PROCESSING,
    /** 执行成功，结果摘要已落到受理记录。 */
    SUCCEEDED,
    /** 执行失败，错误码取自各业务契约的错误码表。 */
    FAILED;

    /** 是否仍在处理中；处理中重复查询返回同一受理状态，不伪造结果。 */
    public boolean processing() {
        return this == PROCESSING;
    }
}
