package com.huoling.wms.masterdata.archive.domain;

/**
 * 受理记录仓储。
 *
 * <p>写入与幂等记录同一本地事务（design「幂等与异步事件」）；查询按受理号取，
 * 数据域由调用方给出作用域键拼进条件，仓储层不做「全量回落」。
 */
public interface ArchiveAcceptanceRepository {

    /** 按受理号取；不存在返回 {@code null}。 */
    ArchiveAcceptance findByAcceptNo(String acceptNo);

    /** 按（作用域、接口、幂等键）取首次受理记录；不存在返回 {@code null}。 */
    ArchiveAcceptance findByIdempotency(String scopeKey, String apiCode, String idempotencyKey);

    /**
     * 插入受理记录。
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 唯一键冲突，表示并发下另一请求已受理
     */
    void insert(ArchiveAcceptance acceptance);

    /** 执行成功：只更新状态与结果列。 */
    void markSucceeded(String acceptNo, String resultJson, java.time.Instant now);

    /** 执行失败：只更新状态、错误码与说明。 */
    void markFailed(String acceptNo, String errorCode, String errorMessage, java.time.Instant now);
}
