package com.huoling.wms.masterdata.archive.domain;

import java.time.Instant;
import java.util.List;

/**
 * 本地消息表仓储。
 *
 * <p>写入与业务同事务；投递器扫待投递记录发往消息队列，失败按 {@code retry_count} 与
 * {@code next_retry_at} 退避补偿（22 T-05、01「本地消息表 + 定时补偿」）。
 *
 * <p>抢占用条件更新：只有把记录从待投递改成已发送成功的实例才继续处理，多副本不会双发。
 */
public interface ArchiveOutboxRepository {

    void insert(ArchiveOutboxMessage message);

    /** 取待投递记录：按（是否到重试时间, 创建时间）升序，最多 {@code limit} 条。 */
    List<ArchiveOutboxMessage> findDispatchable(int limit, Instant now);

    /**
     * 抢占一条待投递记录。
     *
     * @return {@code false} 表示已被其它实例抢走或还没到重试时间，调用方跳过本条
     */
    boolean claim(Long id);

    /** 投递成功：记录发送时间。 */
    void markSent(Long id, Instant now);

    /** 投递失败：累计重试次数、记录错误并按退避时间安排下次重试。 */
    void markFailed(Long id, String error, Instant now);
}
