package com.huoling.wms.masterdata.archive.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 审核留痕只增不改，因此只暴露插入能力（BaseMapper 的更新与删除不被仓储层调用）。 */
@Mapper
public interface SkuReviewMapper extends BaseMapper<SkuReviewPO> {
}
