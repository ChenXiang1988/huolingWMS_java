package com.huoling.wms.masterdata.archive.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveQuery;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.archive.domain.SkuPageCursor;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.SkuArchiveMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.SkuArchivePO;
import com.huoling.wms.masterdata.domain.SkuArchiveDedupRule;
import com.huoling.wms.masterdata.domain.SkuArchiveFields;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuSortDirection;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 商品档案仓储实现。数据域在这里注入：
 * 货主域方法把 {@code owner_id} 拼进条件，{@code ownerId} 为空一律按越权拒绝，不回落成全量（INV-06）。
 */
@Repository
public class SkuArchiveRepositoryImpl implements SkuArchiveRepository {

    private final SkuArchiveMapper mapper;

    public SkuArchiveRepositoryImpl(SkuArchiveMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SkuArchive> findByArchiveNo(String archiveNo) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<SkuArchivePO>()
            .eq(SkuArchivePO::getArchiveNo, archiveNo))).map(this::toDomain);
    }

    @Override
    public Optional<SkuArchive> findByArchiveNoAndOwner(String archiveNo, Long ownerId) {
        if (ownerId == null) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "缺少货主数据域，拒绝访问档案");
        }
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<SkuArchivePO>()
            .eq(SkuArchivePO::getArchiveNo, archiveNo)
            .eq(SkuArchivePO::getOwnerId, ownerId))).map(this::toDomain);
    }

    @Override
    public List<SkuArchive> findByArchiveNos(Collection<String> archiveNos) {
        if (archiveNos == null || archiveNos.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<SkuArchivePO>()
            .in(SkuArchivePO::getArchiveNo, archiveNos)).stream().map(this::toDomain).toList();
    }

    @Override
    public List<SkuArchiveDedupRule.SkuIdentity> findIdentities(Long ownerId) {
        if (ownerId == null) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "缺少货主数据域，拒绝判重查询");
        }
        return mapper.findIdentityRows(ownerId).stream()
            .map(po -> new SkuArchiveDedupRule.SkuIdentity(po.getId(),
                SkuArchiveStatus.valueOf(po.getStatus()), po.getOwnerCode(), po.getBarcode()))
            .toList();
    }

    @Override
    public void insert(SkuArchive archive, long operatorId) {
        SkuArchivePO po = toPO(archive);
        po.setId(archive.id() == null ? IdWorker.getId() : archive.id());
        OffsetDateTime now = OffsetDateTime.ofInstant(
            archive.createdAt() == null ? Instant.now() : archive.createdAt(), ZoneOffset.UTC);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        po.setCreatedBy(operatorId);
        po.setUpdatedBy(operatorId);
        mapper.insert(po);
    }

    @Override
    public boolean update(SkuArchive archive, long expectedVersion, long operatorId) {
        SkuArchivePO po = toPO(archive);
        po.setUpdatedAt(OffsetDateTime.ofInstant(
            archive.updatedAt() == null ? Instant.now() : archive.updatedAt(), ZoneOffset.UTC));
        po.setUpdatedBy(operatorId);
        return mapper.updateWithVersion(po, expectedVersion) == 1;
    }

    @Override
    public List<SkuArchive> page(SkuArchiveQuery query) {
        LambdaQueryWrapper<SkuArchivePO> wrapper = new LambdaQueryWrapper<SkuArchivePO>()
            .eq(query.ownerId() != null, SkuArchivePO::getOwnerId, query.ownerId())
            .eq(query.ownerCode() != null && !query.ownerCode().isBlank(),
                SkuArchivePO::getOwnerCode, query.ownerCode())
            .like(query.name() != null && !query.name().isBlank(), SkuArchivePO::getName, query.name())
            .eq(query.status() != null, SkuArchivePO::getStatus,
                query.status() == null ? null : query.status().name())
            .eq(query.category() != null && !query.category().isBlank(),
                SkuArchivePO::getCategory, query.category())
            .ge(query.updatedFrom() != null, SkuArchivePO::getUpdatedAt,
                offset(query.updatedFrom()))
            .le(query.updatedTo() != null, SkuArchivePO::getUpdatedAt, offset(query.updatedTo()));

        applyOrder(wrapper, query.sort());
        if (query.cursor() != null) {
            applyCursor(wrapper, query.cursor());
        }
        // limit 已由 SkuArchiveQuery 约束在 1 到 100，直接作为字面量拼进 SQL，不接受外部原始串。
        wrapper.last("limit " + query.limit());

        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** 排序：契约 {@code sortBy} 落成档案表的列，主键恒为次级键，保证同一组排序下翻页不重不漏。 */
    private void applyOrder(LambdaQueryWrapper<SkuArchivePO> wrapper, SkuListSort sort) {
        boolean asc = sort.ascending();
        switch (sort.key()) {
            case OWNER_CODE -> {
                if (asc) {
                    wrapper.orderByAsc(SkuArchivePO::getOwnerCode);
                } else {
                    wrapper.orderByDesc(SkuArchivePO::getOwnerCode);
                }
            }
            case NAME -> {
                if (asc) {
                    wrapper.orderByAsc(SkuArchivePO::getName);
                } else {
                    wrapper.orderByDesc(SkuArchivePO::getName);
                }
            }
            default -> {
                if (asc) {
                    wrapper.orderByAsc(SkuArchivePO::getUpdatedAt);
                } else {
                    wrapper.orderByDesc(SkuArchivePO::getUpdatedAt);
                }
            }
        }
        if (asc) {
            wrapper.orderByAsc(SkuArchivePO::getId);
        } else {
            wrapper.orderByDesc(SkuArchivePO::getId);
        }
    }

    /**
     * 游标条件：按游标自带的排序字段与方向比较（游标解码时已与本次请求的排序参数比对过）。
     *
     * <p>「严格大于/小于锚点，或等于锚点时主键继续前进」，与 ORDER BY 完全同序，
     * 因此同值行不会重复也不会漏。
     */
    private void applyCursor(LambdaQueryWrapper<SkuArchivePO> wrapper, SkuPageCursor cursor) {
        boolean asc = cursor.direction() == SkuSortDirection.ASC;
        switch (cursor.key()) {
            case OWNER_CODE -> wrapper.and(nested -> {
                if (asc) {
                    nested.gt(SkuArchivePO::getOwnerCode, cursor.textValue())
                        .or(inner -> inner.eq(SkuArchivePO::getOwnerCode, cursor.textValue())
                            .gt(SkuArchivePO::getId, cursor.id()));
                } else {
                    nested.lt(SkuArchivePO::getOwnerCode, cursor.textValue())
                        .or(inner -> inner.eq(SkuArchivePO::getOwnerCode, cursor.textValue())
                            .lt(SkuArchivePO::getId, cursor.id()));
                }
            });
            case NAME -> wrapper.and(nested -> {
                if (asc) {
                    nested.gt(SkuArchivePO::getName, cursor.textValue())
                        .or(inner -> inner.eq(SkuArchivePO::getName, cursor.textValue())
                            .gt(SkuArchivePO::getId, cursor.id()));
                } else {
                    nested.lt(SkuArchivePO::getName, cursor.textValue())
                        .or(inner -> inner.eq(SkuArchivePO::getName, cursor.textValue())
                            .lt(SkuArchivePO::getId, cursor.id()));
                }
            });
            default -> {
                OffsetDateTime time = offset(cursor.instantValue());
                wrapper.and(nested -> {
                    if (asc) {
                        nested.gt(SkuArchivePO::getUpdatedAt, time)
                            .or(inner -> inner.eq(SkuArchivePO::getUpdatedAt, time)
                                .gt(SkuArchivePO::getId, cursor.id()));
                    } else {
                        nested.lt(SkuArchivePO::getUpdatedAt, time)
                            .or(inner -> inner.eq(SkuArchivePO::getUpdatedAt, time)
                                .lt(SkuArchivePO::getId, cursor.id()));
                    }
                });
            }
        }
    }

    private OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private SkuArchive toDomain(SkuArchivePO po) {
        SkuArchiveFields fields = new SkuArchiveFields(po.getName(), po.getOwnerCode(),
            po.getBarcode(), po.getBaseUnit(), po.getStorageCondition(), po.getCategory(),
            po.getSpecification(), po.getBrand(), po.getBarcodeType(), po.getPackageSpec(),
            po.getPackageConversionRate(), po.getNetWeight(), po.getGrossWeight(), po.getVolume(),
            po.getLength(), po.getWidth(), po.getHeight(),
            Boolean.TRUE.equals(po.getHighValue()), Boolean.TRUE.equals(po.getHazardous()),
            Boolean.TRUE.equals(po.getFragile()), Boolean.TRUE.equals(po.getBatchManaged()),
            Boolean.TRUE.equals(po.getSerialManaged()), po.getShelfLifeDays(), po.getImageUrl(),
            po.getRemark());
        return SkuArchive.restore(po.getId(), po.getOwnerId(), po.getArchiveNo(), fields,
            SkuArchiveStatus.valueOf(po.getStatus()),
            po.getVersion() == null ? 0L : po.getVersion(),
            instant(po.getActivatedAt()), instant(po.getDataTimestamp()), instant(po.getCreatedAt()),
            instant(po.getUpdatedAt()));
    }

    private SkuArchivePO toPO(SkuArchive archive) {
        SkuArchivePO po = new SkuArchivePO();
        po.setId(archive.id());
        po.setOwnerId(archive.ownerId());
        po.setArchiveNo(archive.archiveNo());
        po.setOwnerCode(archive.fields().ownerCode());
        po.setName(archive.fields().name());
        po.setBarcode(archive.fields().barcode());
        po.setBaseUnit(archive.fields().baseUnit());
        po.setStorageCondition(archive.fields().storageCondition());
        po.setCategory(archive.fields().category());
        po.setSpecification(archive.fields().specification());
        po.setBrand(archive.fields().brand());
        po.setBarcodeType(archive.fields().barcodeType());
        po.setPackageSpec(archive.fields().packageSpec());
        po.setPackageConversionRate(archive.fields().packageConversionRate());
        po.setNetWeight(archive.fields().netWeight());
        po.setGrossWeight(archive.fields().grossWeight());
        po.setVolume(archive.fields().volume());
        po.setLength(archive.fields().length());
        po.setWidth(archive.fields().width());
        po.setHeight(archive.fields().height());
        po.setHighValue(archive.fields().highValue());
        po.setHazardous(archive.fields().hazardous());
        po.setFragile(archive.fields().fragile());
        po.setBatchManaged(archive.fields().batchManaged());
        po.setSerialManaged(archive.fields().serialManaged());
        po.setShelfLifeDays(archive.fields().shelfLifeDays());
        po.setImageUrl(archive.fields().imageUrl());
        po.setRemark(archive.fields().remark());
        po.setStatus(archive.status().name());
        po.setDisabledFlag(archive.disabled());
        po.setVersion(archive.version());
        po.setActivatedAt(offset(archive.activatedAt()));
        po.setDataTimestamp(offset(archive.dataTimestamp()));
        return po;
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
