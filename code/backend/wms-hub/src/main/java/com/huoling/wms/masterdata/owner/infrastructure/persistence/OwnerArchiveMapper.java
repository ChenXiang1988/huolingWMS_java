package com.huoling.wms.masterdata.owner.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OwnerArchiveMapper extends BaseMapper<OwnerArchivePO> {

    /**
     * 取下一个货主编号：{@code 'OW' || to_char(nextval(...), 'FM000000')}。
     *
     * <p>FM 只补零不截断，序号超过六位自然变长，与契约「8 至 32 位、不承诺固定位数」一致。
     */
    @Select("select 'OW' || to_char(nextval('seq_wms_owner_no'), 'FM000000')")
    String nextOwnerNo();

    /**
     * 带乐观锁的整行更新：只有库中版本仍等于客户端版本才写入，返回 0 即版本冲突。
     *
     * <p>不写 {@code id}、{@code owner_no}、{@code created_at}、{@code created_by}：
     * 编号与主键创建后不可变（OI-01），数据库侧触发器还会再拦一层。
     */
    @Update("""
        update wms_owner
           set owner_name        = #{po.ownerName},
               contact_name      = #{po.contactName},
               contact_phone     = #{po.contactPhone},
               credit_code       = #{po.creditCode},
               status            = #{po.status},
               status_action     = #{po.statusAction},
               status_reason     = #{po.statusReason},
               status_changed_at = #{po.statusChangedAt},
               status_changed_by = #{po.statusChangedBy},
               version           = #{po.version},
               updated_at        = #{po.updatedAt},
               updated_by        = #{po.updatedBy}
         where id = #{po.id}
           and version = #{expectedVersion}
        """)
    int updateWithVersion(@Param("po") OwnerArchivePO po,
                          @Param("expectedVersion") long expectedVersion);
}
