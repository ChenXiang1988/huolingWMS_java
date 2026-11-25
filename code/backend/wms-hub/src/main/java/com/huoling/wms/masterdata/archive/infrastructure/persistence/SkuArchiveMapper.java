package com.huoling.wms.masterdata.archive.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkuArchiveMapper extends BaseMapper<SkuArchivePO> {

    /** 同货主的档案身份投影，只取判重需要的四列。 */
    @Select("""
        select id, status, owner_code, barcode
          from hub_sku_archive
         where owner_id = #{ownerId}
        """)
    List<SkuArchivePO> findIdentityRows(@Param("ownerId") Long ownerId);

    /**
     * 带乐观锁的整行更新：只有库中版本仍等于客户端版本才写入，返回 0 即版本冲突。
     *
     * <p>不写 {@code owner_id}、{@code archive_no}、{@code created_at}、{@code created_by}：
     * 归属与系统编号创建后不可变（INV-06），数据库侧触发器还会再拦一层。
     */
    @Update("""
        update hub_sku_archive
           set owner_code              = #{po.ownerCode},
               name                    = #{po.name},
               barcode                 = #{po.barcode},
               base_unit               = #{po.baseUnit},
               storage_condition       = #{po.storageCondition},
               category                = #{po.category},
               specification           = #{po.specification},
               brand                   = #{po.brand},
               barcode_type            = #{po.barcodeType},
               package_spec            = #{po.packageSpec},
               package_conversion_rate = #{po.packageConversionRate},
               net_weight              = #{po.netWeight},
               gross_weight            = #{po.grossWeight},
               volume                  = #{po.volume},
               length                  = #{po.length},
               width                   = #{po.width},
               height                  = #{po.height},
               high_value              = #{po.highValue},
               hazardous               = #{po.hazardous},
               fragile                 = #{po.fragile},
               batch_managed           = #{po.batchManaged},
               serial_managed          = #{po.serialManaged},
               shelf_life_days         = #{po.shelfLifeDays},
               image_url               = #{po.imageUrl},
               remark                  = #{po.remark},
               status                  = #{po.status},
               disabled_flag           = #{po.disabledFlag},
               version                 = #{po.version},
               activated_at            = #{po.activatedAt},
               data_timestamp          = #{po.dataTimestamp},
               updated_at              = #{po.updatedAt},
               updated_by              = #{po.updatedBy}
         where id = #{po.id}
           and version = #{expectedVersion}
        """)
    int updateWithVersion(@Param("po") SkuArchivePO po,
                          @Param("expectedVersion") long expectedVersion);
}
