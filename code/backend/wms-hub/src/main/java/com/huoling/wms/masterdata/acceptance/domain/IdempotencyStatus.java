package com.huoling.wms.masterdata.acceptance.domain;

/** 幂等记录状态：处理中可重试，已成功可回放首次结果，失败允许同键重来。 */
public enum IdempotencyStatus {

    /** 已占位，业务尚未完成：同键请求按可重试错误返回。 */
    IN_PROGRESS,
    /** 业务已完成：同键同指纹回放首次结果。 */
    SUCCEEDED,
    /** 业务失败：不占用幂等键，允许同键重试。 */
    FAILED
}
