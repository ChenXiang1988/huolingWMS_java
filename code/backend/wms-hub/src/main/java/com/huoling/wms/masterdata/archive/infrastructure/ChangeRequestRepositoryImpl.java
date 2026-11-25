package com.huoling.wms.masterdata.archive.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ChangeRequest;
import com.huoling.wms.masterdata.archive.domain.ChangeRequestQuery;
import com.huoling.wms.masterdata.archive.domain.ChangeRequestRepository;
import com.huoling.wms.masterdata.archive.domain.SkuPageCursor;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.ChangeRequestMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.ChangeRequestPO;
import com.huoling.wms.masterdata.domain.SkuChangeRequestStatus;
import com.huoling.wms.masterdata.domain.SkuChangeableField;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuSortDirection;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 变更申请仓储实现。本仓域方法把 {@code warehouse_id} 拼进条件，
 * {@code warehouseId} 为空一律按越权拒绝，不回落成全量（INV-06、INV-19）。
 */
@Repository
public class ChangeRequestRepositoryImpl implements ChangeRequestRepository {

    private final ChangeRequestMapper mapper;

    public ChangeRequestRepositoryImpl(ChangeRequestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ChangeRequest> findByRequestNo(String requestNo) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ChangeRequestPO>()
            .eq(ChangeRequestPO::getRequestNo, requestNo))).map(this::toDomain);
    }

    @Override
    public Optional<ChangeRequest> findByRequestNoAndWarehouse(String requestNo, Long warehouseId) {
        if (warehouseId == null) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE, "缺少本仓数据域，拒绝访问申请");
        }
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ChangeRequestPO>()
            .eq(ChangeRequestPO::getRequestNo, requestNo)
            .eq(ChangeRequestPO::getWarehouseId, warehouseId))).map(this::toDomain);
    }

    @Override
    public boolean hasPending(Long archiveId) {
        return mapper.countPending(archiveId) > 0;
    }

    @Override
    public void insert(ChangeRequest request, long operatorId) {
        ChangeRequestPO po = toPO(request);
        po.setId(request.id() == null ? IdWorker.getId() : request.id());
        OffsetDateTime now = offset(request.createdAt());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        po.setCreatedBy(operatorId);
        po.setUpdatedBy(operatorId);
        mapper.insert(po);
    }

    @Override
    public boolean update(ChangeRequest request, long expectedVersion, long operatorId) {
        ChangeRequestPO po = toPO(request);
        po.setUpdatedAt(offset(request.updatedAt()));
        po.setUpdatedBy(operatorId);
        return mapper.updateWithVersion(po, expectedVersion) == 1;
    }

    @Override
    public List<ChangeRequest> page(ChangeRequestQuery query) {
        LambdaQueryWrapper<ChangeRequestPO> wrapper = new LambdaQueryWrapper<ChangeRequestPO>()
            .eq(query.ownerId() != null, ChangeRequestPO::getOwnerId, query.ownerId())
            .eq(query.siteId() != null, ChangeRequestPO::getWarehouseId, query.siteId())
            .eq(query.status() != null, ChangeRequestPO::getStatus,
                query.status() == null ? null : query.status().name())
            .eq(query.requestNo() != null && !query.requestNo().isBlank(),
                ChangeRequestPO::getRequestNo, query.requestNo())
            .eq(query.archiveId() != null && !query.archiveId().isBlank(),
                ChangeRequestPO::getArchiveNo, query.archiveId());

        applyOrder(wrapper, query.sort());
        if (query.cursor() != null) {
            applyCursor(wrapper, query.cursor());
        }
        wrapper.last("limit " + query.limit());

        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /**
     * 排序：契约 {@code sortBy} 在变更申请列表上按申请字段解释（契约字段结构表），
     * 不映射到档案表列——申请列表的排序维度是申请自带的创建时间、归属货主与变更字段。
     * 主键恒为次级键，同一组排序下翻页不重不漏。
     */
    private void applyOrder(LambdaQueryWrapper<ChangeRequestPO> wrapper, SkuListSort sort) {
        boolean asc = sort.ascending();
        switch (sort.key()) {
            case OWNER_CODE -> {
                if (asc) {
                    wrapper.orderByAsc(ChangeRequestPO::getOwnerId);
                } else {
                    wrapper.orderByDesc(ChangeRequestPO::getOwnerId);
                }
            }
            case NAME -> {
                if (asc) {
                    wrapper.orderByAsc(ChangeRequestPO::getFieldName);
                } else {
                    wrapper.orderByDesc(ChangeRequestPO::getFieldName);
                }
            }
            default -> {
                if (asc) {
                    wrapper.orderByAsc(ChangeRequestPO::getCreatedAt);
                } else {
                    wrapper.orderByDesc(ChangeRequestPO::getCreatedAt);
                }
            }
        }
        if (asc) {
            wrapper.orderByAsc(ChangeRequestPO::getId);
        } else {
            wrapper.orderByDesc(ChangeRequestPO::getId);
        }
    }

    /** 游标条件：与 {@link #applyOrder} 同一列同一方向比较，同值行靠主键继续前进。 */
    private void applyCursor(LambdaQueryWrapper<ChangeRequestPO> wrapper, SkuPageCursor cursor) {
        boolean asc = cursor.direction() == SkuSortDirection.ASC;
        switch (cursor.key()) {
            case OWNER_CODE -> wrapper.and(nested -> {
                if (asc) {
                    nested.gt(ChangeRequestPO::getOwnerId, cursor.longValue())
                        .or(inner -> inner.eq(ChangeRequestPO::getOwnerId, cursor.longValue())
                            .gt(ChangeRequestPO::getId, cursor.id()));
                } else {
                    nested.lt(ChangeRequestPO::getOwnerId, cursor.longValue())
                        .or(inner -> inner.eq(ChangeRequestPO::getOwnerId, cursor.longValue())
                            .lt(ChangeRequestPO::getId, cursor.id()));
                }
            });
            case NAME -> wrapper.and(nested -> {
                if (asc) {
                    nested.gt(ChangeRequestPO::getFieldName, cursor.textValue())
                        .or(inner -> inner.eq(ChangeRequestPO::getFieldName, cursor.textValue())
                            .gt(ChangeRequestPO::getId, cursor.id()));
                } else {
                    nested.lt(ChangeRequestPO::getFieldName, cursor.textValue())
                        .or(inner -> inner.eq(ChangeRequestPO::getFieldName, cursor.textValue())
                            .lt(ChangeRequestPO::getId, cursor.id()));
                }
            });
            default -> {
                OffsetDateTime createdAt = offset(cursor.instantValue());
                wrapper.and(nested -> {
                    if (asc) {
                        nested.gt(ChangeRequestPO::getCreatedAt, createdAt)
                            .or(inner -> inner.eq(ChangeRequestPO::getCreatedAt, createdAt)
                                .gt(ChangeRequestPO::getId, cursor.id()));
                    } else {
                        nested.lt(ChangeRequestPO::getCreatedAt, createdAt)
                            .or(inner -> inner.eq(ChangeRequestPO::getCreatedAt, createdAt)
                                .lt(ChangeRequestPO::getId, cursor.id()));
                    }
                });
            }
        }
    }

    private ChangeRequest toDomain(ChangeRequestPO po) {
        return ChangeRequest.restore(po.getId(), po.getRequestNo(), po.getArchiveId(),
            po.getArchiveNo(), po.getOwnerId(), po.getWarehouseId(),
            SkuChangeableField.valueOf(po.getFieldName()), po.getTargetValue(), po.getReason(),
            SkuChangeRequestStatus.valueOf(po.getStatus()), po.getReviewReason(),
            po.getArchiveVersion(), po.getVersion() == null ? 0L : po.getVersion(),
            instant(po.getCreatedAt()), instant(po.getUpdatedAt()));
    }

    private ChangeRequestPO toPO(ChangeRequest request) {
        ChangeRequestPO po = new ChangeRequestPO();
        po.setId(request.id());
        po.setRequestNo(request.requestNo());
        po.setArchiveId(request.archiveId());
        po.setArchiveNo(request.archiveNo());
        po.setOwnerId(request.ownerId());
        po.setWarehouseId(request.warehouseId());
        po.setFieldName(request.field().name());
        po.setTargetValue(request.targetValue());
        po.setReason(request.reason());
        po.setStatus(request.status().name());
        po.setReviewReason(request.reviewReason());
        po.setArchiveVersion(request.archiveVersion());
        po.setVersion(request.version());
        return po;
    }

    private OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
