package com.huoling.wms.masterdata.acceptance.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 受理记录仓储。
 *
 * <p>写入与幂等记录同一本地事务（design「事务边界与并发控制」）；查询按受理号取，
 * 作用域、接口号与目标货主由调用方给出并拼进条件，仓储层不做「全量回落」。
 */
public interface AcceptanceRepository {

    /**
     * 按受理号取，且只在该接口号集合内可见（契约 1.6「跨业务不可见」的落点）。
     *
     * <p>数据域作用域不在这里判定：调用方先用 {@link AcceptanceQuery} 收窄作用域、接口号
     * 与目标货主，拿到记录后再比对 {@link Acceptance#scopeKey()}。
     *
     * @param apiCodes        允许的接口号；空集合表示不允许查出任何记录（不回落成全量）
     * @param subjectOwnerIds 允许的目标货主；{@code null} 表示不按目标货主收窄，
     *                        空集合表示不允许查出任何记录
     * @return 不存在或不在可见范围内返回 {@code null}
     */
    Acceptance findByAcceptNo(String acceptNo, Set<String> apiCodes, Set<Long> subjectOwnerIds);

    /** 按（作用域、接口、幂等键）取首次受理记录；不存在返回 {@code null}。 */
    Acceptance findByIdempotency(String scopeKey, String apiCode, String idempotencyKey);

    /**
     * 仍在处理中的受理单条数（受理积压指标，tasks 1.18）。
     *
     * @param apiCodes 统计范围；空集合表示不统计（返回 0，不回落成全量）
     */
    long countProcessing(Set<String> apiCodes);

    /**
     * 插入受理记录。
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 唯一键冲突，表示并发下另一请求已受理
     */
    void insert(Acceptance acceptance);

    /** 执行成功：只更新状态与结果列。 */
    void markSucceeded(String acceptNo, String resultJson, Instant now);

    /** 执行失败：只更新状态、错误码与说明。 */
    void markFailed(String acceptNo, String errorCode, String errorMessage, Instant now);
}
