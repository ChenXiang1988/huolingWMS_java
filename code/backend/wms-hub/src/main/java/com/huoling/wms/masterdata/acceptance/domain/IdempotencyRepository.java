package com.huoling.wms.masterdata.acceptance.domain;

import java.time.Instant;

/** 幂等记录仓储。作用域键由调用方按端给出：货主端取归属货主，运营端取 {@code HUB}，单仓端取本仓。 */
public interface IdempotencyRepository {

    /** 按（作用域、接口、键）取记录；不存在返回 {@code null}。 */
    IdempotencyRecord find(String scopeKey, String apiCode, String idempotencyKey);

    /**
     * 占位：插入处理中的记录。
     *
     * @return {@code false} 表示唯一键已被并发请求占用，调用方回到读取分支重新判定
     */
    boolean reserve(String scopeKey, String apiCode, String idempotencyKey, String fingerprint,
                    Instant createdAt, Instant expiresAt);

    /** 业务成功：记录首次结果。 */
    void markSucceeded(String scopeKey, String apiCode, String idempotencyKey, String resultJson);

    /** 业务失败：释放幂等键，允许同键重试。 */
    void release(String scopeKey, String apiCode, String idempotencyKey);
}
