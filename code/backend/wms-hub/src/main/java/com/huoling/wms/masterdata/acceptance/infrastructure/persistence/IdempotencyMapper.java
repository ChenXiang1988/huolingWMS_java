package com.huoling.wms.masterdata.acceptance.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IdempotencyMapper extends BaseMapper<IdempotencyPO> {

    /**
     * 占位写入：唯一键（作用域、接口、键）已被占用时什么也不做。
     *
     * <p>为什么不用「插入失败抛异常再捕获」：PostgreSQL 里语句报错会把当前事务置为 aborted，
     * 之后同一事务的任何读取都以 SQLSTATE 25P02 失败，并发重复请求就会落到契约外的 500。
     * {@code on conflict do nothing} 不报错，事务仍可用，调用方可以继续回读首次记录
     * （design「幂等与异步受理」：重复请求回放首次受理号，处理中返回可重试错误）。
     *
     * @return 1 表示占位成功（首次受理），0 表示唯一键已被并发请求占用
     */
    @Insert("""
        insert into hub_idempotency (id, scope_key, api_code, idempotency_key, fingerprint,
            status, result_json, error_code, created_at, expires_at)
        values (#{id}, #{scopeKey}, #{apiCode}, #{idempotencyKey}, #{fingerprint},
            #{status}, null, null, #{createdAt}, #{expiresAt})
        on conflict (scope_key, api_code, idempotency_key) do nothing
        """)
    int insertIfAbsent(IdempotencyPO record);

    /** 业务成功：写入首次结果，后续同键同指纹回放它。 */
    @Update("""
        update hub_idempotency
           set status = 'SUCCEEDED',
               result_json = #{resultJson},
               error_code = null
         where scope_key = #{scopeKey}
           and api_code = #{apiCode}
           and idempotency_key = #{idempotencyKey}
        """)
    int markSucceeded(@Param("scopeKey") String scopeKey,
                      @Param("apiCode") String apiCode,
                      @Param("idempotencyKey") String idempotencyKey,
                      @Param("resultJson") String resultJson);

    /** 业务失败：释放幂等键，允许同键重试（失败不占用受理结果）。 */
    @Delete("""
        delete from hub_idempotency
         where scope_key = #{scopeKey}
           and api_code = #{apiCode}
           and idempotency_key = #{idempotencyKey}
        """)
    int release(@Param("scopeKey") String scopeKey,
                @Param("apiCode") String apiCode,
                @Param("idempotencyKey") String idempotencyKey);
}
