package com.huoling.wms.masterdata.archive.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/** 本地消息表：应用只插入与标记投递状态，不删除（22 T-05）。 */
@Mapper
public interface OutboxMapper extends BaseMapper<OutboxPO> {

    /**
     * 待投递记录：{@code PENDING} 且重试时间已到。
     *
     * <p>{@code FAILED} 也参与重试：失败不是终态，退避后继续投递，直到成功为止，
     * 由 {@code retry_count} 与告警兜住「一直失败」的场景。
     */
    @Select("""
        select * from hub_outbox
         where status in ('PENDING', 'FAILED')
           and (next_retry_at is null or next_retry_at <= #{now})
         order by created_at asc
         limit #{limit}
        """)
    List<OutboxPO> findDispatchable(@Param("limit") int limit, @Param("now") OffsetDateTime now);

    /**
     * 抢占：把待投递改成已发送，条件不满足即说明已被其它实例抢走（多副本不双发）。
     */
    @Update("""
        update hub_outbox
           set status = 'SENT', dispatched_at = #{now}
         where id = #{id}
           and status in ('PENDING', 'FAILED')
           and (next_retry_at is null or next_retry_at <= #{now})
        """)
    int claim(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * 投递失败：累计重试次数并按指数退避安排下次重试（30 秒起，最多 64 倍即 32 分钟）。
     *
     * <p>退避在 SQL 里算，投递器不必把重试次数读回内存，多副本并发也不会把退避算错。
     */
    @Update("""
        update hub_outbox
           set status = 'FAILED',
               retry_count = retry_count + 1,
               last_error = #{error},
               next_retry_at = #{now} + make_interval(secs => least(power(2, retry_count), 64) * 30)
         where id = #{id}
        """)
    int markFailed(@Param("id") Long id,
                   @Param("error") String error,
                   @Param("now") OffsetDateTime now);
}
