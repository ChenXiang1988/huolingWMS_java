package com.huoling.wms.site.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 单仓受理记录读写；状态推进只允许从 {@code PROCESSING} 出发。 */
@Mapper
public interface SiteAcceptanceMapper {

    @Select("""
        select id, accept_no as "acceptNo", site_id as "siteId", api_code as "apiCode",
               idempotency_key as "idempotencyKey", fingerprint, status_snapshot as "statusSnapshot",
               accept_status as "acceptStatus", result_json as "resultJson",
               error_code as "errorCode", error_message as "errorMessage",
               created_at as "createdAt", updated_at as "updatedAt"
          from site_archive_acceptance
         where site_id = #{siteId}
           and api_code = #{apiCode}
           and idempotency_key = #{idempotencyKey}
        """)
    Map<String, Object> findByIdempotency(@Param("siteId") Long siteId,
                                          @Param("apiCode") String apiCode,
                                          @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select id, accept_no as "acceptNo", site_id as "siteId", api_code as "apiCode",
               idempotency_key as "idempotencyKey", fingerprint, status_snapshot as "statusSnapshot",
               accept_status as "acceptStatus", result_json as "resultJson",
               error_code as "errorCode", error_message as "errorMessage",
               created_at as "createdAt", updated_at as "updatedAt"
          from site_archive_acceptance
         where site_id = #{siteId}
           and accept_no = #{acceptNo}
        """)
    Map<String, Object> findByAcceptNo(@Param("siteId") Long siteId,
                                       @Param("acceptNo") String acceptNo);

    @Insert("""
        insert into site_archive_acceptance (id, accept_no, site_id, api_code, idempotency_key,
            fingerprint, status_snapshot, accept_status, result_json, error_code, error_message,
            created_at, updated_at)
        values (#{s.id}, #{s.acceptNo}, #{s.siteId}, #{s.apiCode}, #{s.idempotencyKey},
            #{s.fingerprint}, #{s.statusSnapshot}, #{s.acceptStatus}, #{s.resultJson},
            #{s.errorCode}, #{s.errorMessage}, #{s.createdAt}, #{s.updatedAt})
        """)
    int insert(@Param("s") Map<String, Object> acceptance);

    @Update("""
        update site_archive_acceptance
           set accept_status = 'SUCCEEDED',
               result_json = #{resultJson},
               error_code = null,
               error_message = null,
               updated_at = #{now}
         where accept_no = #{acceptNo}
           and accept_status = 'PROCESSING'
        """)
    int markSucceeded(@Param("acceptNo") String acceptNo,
                      @Param("resultJson") String resultJson,
                      @Param("now") OffsetDateTime now);

    @Update("""
        update site_archive_acceptance
           set accept_status = 'FAILED',
               error_code = #{errorCode},
               error_message = #{errorMessage},
               updated_at = #{now}
         where accept_no = #{acceptNo}
           and accept_status = 'PROCESSING'
        """)
    int markFailed(@Param("acceptNo") String acceptNo,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") OffsetDateTime now);

    /** 超时仍停在处理中的受理号（tasks 1.18 的补偿扫描），最陈旧的先收尾。 */
    @Select("""
        select accept_no
          from site_archive_acceptance
         where accept_status = 'PROCESSING'
           and updated_at < #{deadline}
         order by updated_at asc
         limit #{limit}
        """)
    List<String> findProcessingBefore(@Param("deadline") OffsetDateTime deadline,
                                     @Param("limit") int limit);
}
