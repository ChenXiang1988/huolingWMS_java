package com.huoling.wms.masterdata.archive.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/** 受理记录读写；状态推进只允许从 {@code PROCESSING} 出发，重复更新不会覆盖已终结的记录。 */
@Mapper
public interface ArchiveAcceptanceMapper extends BaseMapper<ArchiveAcceptancePO> {

    @Update("""
        update hub_archive_acceptance
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
        update hub_archive_acceptance
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

    /**
     * 超时仍停在处理中的受理号（tasks 1.18 的补偿扫描）。
     *
     * <p>借 {@code idx_hub_archive_acceptance_status (accept_status, created_at)} 先按状态收窄，
     * 再按更新时间过滤，避免全表扫；排序固定按更新时间，最陈旧的先收尾。
     */
    @Select("""
        select accept_no
          from hub_archive_acceptance
         where accept_status = 'PROCESSING'
           and updated_at < #{deadline}
         order by updated_at asc
         limit #{limit}
        """)
    List<String> findProcessingBefore(@Param("deadline") OffsetDateTime deadline,
                                     @Param("limit") int limit);
}
