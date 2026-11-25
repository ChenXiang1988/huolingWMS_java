package com.huoling.wms.masterdata.archive.domain;

import java.time.Instant;

/**
 * 幂等记录（作用域：归属货主 / 运营端 / 本仓 加接口加键）。
 *
 * @param fingerprint 请求指纹，覆盖接口与请求体，不含幂等键本身
 * @param status      处理中、已成功或已失败
 * @param resultJson  首次受理结果的 JSON，回放时原样返回
 * @param expiresAt   窗口到期时间，到期即视为无记录（契约：窗口不少于二十四小时）
 */
public record IdempotencyRecord(
        String fingerprint,
        IdempotencyStatus status,
        String resultJson,
        Instant expiresAt) {
}
