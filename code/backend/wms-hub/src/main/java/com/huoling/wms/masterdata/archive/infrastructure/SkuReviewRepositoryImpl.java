package com.huoling.wms.masterdata.archive.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.archive.domain.SkuReviewRecord;
import com.huoling.wms.masterdata.archive.domain.SkuReviewRepository;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.SkuReviewMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.SkuReviewPO;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 审核留痕写入：只插入，不提供更新与删除（INV-18）。 */
@Repository
public class SkuReviewRepositoryImpl implements SkuReviewRepository {

    private final SkuReviewMapper mapper;

    public SkuReviewRepositoryImpl(SkuReviewMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(SkuReviewRecord record) {
        SkuReviewPO po = new SkuReviewPO();
        po.setId(record.id() == null ? IdWorker.getId() : record.id());
        po.setArchiveId(record.archiveId());
        po.setArchiveNo(record.archiveNo());
        po.setOwnerId(record.ownerId());
        po.setAction(record.action().name());
        po.setFromStatus(record.fromStatus().name());
        po.setToStatus(record.toStatus().name());
        po.setReason(record.reason());
        po.setVersion(record.version());
        po.setOperatorId(record.operatorId());
        po.setCreatedAt(OffsetDateTime.ofInstant(record.createdAt(), ZoneOffset.UTC));
        mapper.insert(po);
    }
}
