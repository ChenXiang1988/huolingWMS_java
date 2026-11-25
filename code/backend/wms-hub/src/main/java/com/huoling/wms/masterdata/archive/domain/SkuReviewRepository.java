package com.huoling.wms.masterdata.archive.domain;

/** 审核留痕仓储：只增不改，不提供更新与删除。 */
public interface SkuReviewRepository {

    void insert(SkuReviewRecord record);
}
