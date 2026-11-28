package com.huoling.wms.masterdata.owner.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.domain.OwnerAction;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerListSortKey;
import com.huoling.wms.masterdata.domain.OwnerSortDirection;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.owner.domain.OwnerArchive;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveQuery;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveRepository;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerPageCursor;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusChange;
import com.huoling.wms.masterdata.owner.infrastructure.persistence.OwnerArchiveMapper;
import com.huoling.wms.masterdata.owner.infrastructure.persistence.OwnerArchivePO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 货主档案仓储实现。数据域在这里注入：全域不加条件，指定列表加 {@code id in (...)}；
 * 域为空一律按越权拒绝，不回落成全量（INV-19、OI-08）。
 *
 * <p>本实现不提供删除：货主档案不物理删除，库层还 {@code revoke delete, truncate}（OI-06、INV-18）。
 */
@Repository
public class OwnerArchiveRepositoryImpl implements OwnerArchiveRepository {

    private final OwnerArchiveMapper mapper;

    public OwnerArchiveRepositoryImpl(OwnerArchiveMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<OwnerArchive> findByOwnerNo(String ownerNo, OwnerScope scope) {
        requireScope(scope);
        if (ownerNo == null || ownerNo.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<OwnerArchivePO> wrapper = new LambdaQueryWrapper<OwnerArchivePO>()
            .eq(OwnerArchivePO::getOwnerNo, ownerNo)
            // 直接读同一个布尔量传进来的条件，不做「空列表即全部」的隐式回落。
            .in(!scope.allOwners(), OwnerArchivePO::getId, scope.ownerIds());
        return Optional.ofNullable(mapper.selectOne(wrapper)).map(this::toDomain);
    }

    @Override
    public List<OwnerArchive> page(OwnerArchiveQuery query) {
        OwnerScope scope = query.scope();
        LambdaQueryWrapper<OwnerArchivePO> wrapper = new LambdaQueryWrapper<OwnerArchivePO>()
            .eq(!blank(query.ownerNo()), OwnerArchivePO::getOwnerNo, query.ownerNo())
            .like(!blank(query.ownerName()), OwnerArchivePO::getOwnerName, query.ownerName())
            .eq(!blank(query.creditCode()), OwnerArchivePO::getCreditCode, query.creditCode())
            .eq(query.status() != null, OwnerArchivePO::getStatus,
                query.status() == null ? null : query.status().name())
            .ge(query.updatedFrom() != null, OwnerArchivePO::getUpdatedAt,
                offset(query.updatedFrom()))
            .le(query.updatedTo() != null, OwnerArchivePO::getUpdatedAt, offset(query.updatedTo()))
            .in(!scope.allOwners(), OwnerArchivePO::getId, scope.ownerIds());

        applyOrder(wrapper, query);
        applyCursor(wrapper, query.cursor());
        // limit 已由 OwnerArchiveQuery 约束在 1 到 100（多取一条时为 101），直接作为字面量拼进 SQL。
        wrapper.last("limit " + query.limit());

        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    @Override
    public void insert(OwnerArchive archive) {
        OwnerArchivePO po = toPO(archive);
        po.setId(archive.id() == null ? IdWorker.getId() : archive.id());
        po.setCreatedAt(offset(archive.createdAt()));
        po.setCreatedBy(archive.createdBy());
        po.setUpdatedAt(offset(archive.updatedAt()));
        po.setUpdatedBy(archive.updatedBy());
        mapper.insert(po);
    }

    @Override
    public boolean update(OwnerArchive archive, long expectedVersion) {
        return mapper.updateWithVersion(toPO(archive), expectedVersion) == 1;
    }

    /** 排序：契约 {@code sortBy} 落成列，货主编号恒为次级键，保证同一组排序下翻页不重不漏。 */
    private void applyOrder(LambdaQueryWrapper<OwnerArchivePO> wrapper, OwnerArchiveQuery query) {
        boolean asc = query.sort().ascending();
        switch (query.sort().key()) {
            case OWNER_NAME -> {
                if (asc) {
                    wrapper.orderByAsc(OwnerArchivePO::getOwnerName);
                } else {
                    wrapper.orderByDesc(OwnerArchivePO::getOwnerName);
                }
            }
            case UPDATE_TIME -> {
                if (asc) {
                    wrapper.orderByAsc(OwnerArchivePO::getUpdatedAt);
                } else {
                    wrapper.orderByDesc(OwnerArchivePO::getUpdatedAt);
                }
            }
            default -> {
                if (asc) {
                    wrapper.orderByAsc(OwnerArchivePO::getOwnerNo);
                } else {
                    wrapper.orderByDesc(OwnerArchivePO::getOwnerNo);
                }
            }
        }
        // 次级键只在排序键不是货主编号时补：编号唯一，重复排同一列没有意义。
        if (query.sort().key() != OwnerListSortKey.OWNER_NO) {
            if (asc) {
                wrapper.orderByAsc(OwnerArchivePO::getOwnerNo);
            } else {
                wrapper.orderByDesc(OwnerArchivePO::getOwnerNo);
            }
        }
    }

    /**
     * 游标条件：按游标自带的排序字段与方向比较（解码时已与本次请求的排序参数比对过）。
     *
     * <p>「严格大于/小于锚点，或等于锚点时货主编号继续前进」，与 ORDER BY 完全同序，
     * 因此同值行不会重复也不会漏。
     */
    private void applyCursor(LambdaQueryWrapper<OwnerArchivePO> wrapper, OwnerPageCursor cursor) {
        if (cursor == null) {
            return;
        }
        boolean asc = cursor.direction() == OwnerSortDirection.ASC;
        switch (cursor.key()) {
            case OWNER_NAME -> wrapper.and(nested -> {
                if (asc) {
                    nested.gt(OwnerArchivePO::getOwnerName, cursor.textValue())
                        .or(inner -> inner.eq(OwnerArchivePO::getOwnerName, cursor.textValue())
                            .gt(OwnerArchivePO::getOwnerNo, cursor.ownerNo()));
                } else {
                    nested.lt(OwnerArchivePO::getOwnerName, cursor.textValue())
                        .or(inner -> inner.eq(OwnerArchivePO::getOwnerName, cursor.textValue())
                            .lt(OwnerArchivePO::getOwnerNo, cursor.ownerNo()));
                }
            });
            case UPDATE_TIME -> {
                OffsetDateTime time = offset(cursor.instantValue());
                wrapper.and(nested -> {
                    if (asc) {
                        nested.gt(OwnerArchivePO::getUpdatedAt, time)
                            .or(inner -> inner.eq(OwnerArchivePO::getUpdatedAt, time)
                                .gt(OwnerArchivePO::getOwnerNo, cursor.ownerNo()));
                    } else {
                        nested.lt(OwnerArchivePO::getUpdatedAt, time)
                            .or(inner -> inner.eq(OwnerArchivePO::getUpdatedAt, time)
                                .lt(OwnerArchivePO::getOwnerNo, cursor.ownerNo()));
                    }
                });
            }
            default -> {
                if (asc) {
                    wrapper.gt(OwnerArchivePO::getOwnerNo, cursor.textValue());
                } else {
                    wrapper.lt(OwnerArchivePO::getOwnerNo, cursor.textValue());
                }
            }
        }
    }

    private void requireScope(OwnerScope scope) {
        if (scope == null) {
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER, "缺少货主数据域，拒绝访问");
        }
        scope.requireNotEmpty();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private OwnerArchive toDomain(OwnerArchivePO po) {
        OwnerFields fields = new OwnerFields(po.getOwnerName(), po.getContactName(),
            po.getContactPhone(), po.getCreditCode());
        OwnerStatusChange lastStatusChange = new OwnerStatusChange(
            OwnerAction.valueOf(po.getStatusAction()), po.getStatusReason(),
            po.getStatusChangedBy() == null ? 0L : po.getStatusChangedBy(),
            instant(po.getStatusChangedAt()));
        return OwnerArchive.restore(po.getId(), po.getOwnerNo(), fields,
            OwnerStatus.valueOf(po.getStatus()), lastStatusChange,
            po.getVersion() == null ? 0L : po.getVersion(),
            instant(po.getCreatedAt()), po.getCreatedBy() == null ? 0L : po.getCreatedBy(),
            instant(po.getUpdatedAt()), po.getUpdatedBy() == null ? 0L : po.getUpdatedBy());
    }

    private OwnerArchivePO toPO(OwnerArchive archive) {
        OwnerArchivePO po = new OwnerArchivePO();
        po.setId(archive.id());
        po.setOwnerNo(archive.ownerNo());
        po.setOwnerName(archive.fields().ownerName());
        po.setContactName(archive.fields().contactName());
        po.setContactPhone(archive.fields().contactPhone());
        po.setCreditCode(archive.fields().creditCode());
        po.setStatus(archive.status().name());
        po.setStatusAction(archive.lastStatusChange().action().name());
        po.setStatusReason(archive.lastStatusChange().reason());
        po.setStatusChangedAt(offset(archive.lastStatusChange().changedAt()));
        po.setStatusChangedBy(archive.lastStatusChange().operatorId());
        po.setVersion(archive.version());
        po.setCreatedAt(offset(archive.createdAt()));
        po.setCreatedBy(archive.createdBy());
        po.setUpdatedAt(offset(archive.updatedAt()));
        po.setUpdatedBy(archive.updatedBy());
        return po;
    }

    private OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
