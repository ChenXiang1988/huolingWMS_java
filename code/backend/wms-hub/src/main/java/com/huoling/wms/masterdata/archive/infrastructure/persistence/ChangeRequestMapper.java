package com.huoling.wms.masterdata.archive.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChangeRequestMapper extends BaseMapper<ChangeRequestPO> {

    /** 该档案是否已有待审核申请；部分唯一索引是最终兜底，这里用于先给出明确错误码。 */
    @Select("""
        select count(*)
          from hub_product_change_request
         where archive_id = #{archiveId}
           and status = 'PENDING'
        """)
    long countPending(@Param("archiveId") Long archiveId);

    /** 带乐观锁的更新，返回 0 表示申请已被他人审核。 */
    @Update("""
        update hub_product_change_request
           set status          = #{po.status},
               review_reason   = #{po.reviewReason},
               archive_version = #{po.archiveVersion},
               version         = #{po.version},
               updated_at      = #{po.updatedAt},
               updated_by      = #{po.updatedBy}
         where id = #{po.id}
           and version = #{expectedVersion}
        """)
    int updateWithVersion(@Param("po") ChangeRequestPO po,
                          @Param("expectedVersion") long expectedVersion);
}
