package com.huoling.wms.site.domain;

/** 受理状态（契约「字段结构与枚举」）：处理中、成功、失败。 */
public enum AcceptanceState {

    PROCESSING,
    SUCCEEDED,
    FAILED;

    public boolean processing() {
        return this == PROCESSING;
    }
}
