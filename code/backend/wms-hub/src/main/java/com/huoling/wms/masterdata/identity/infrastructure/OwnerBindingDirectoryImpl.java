package com.huoling.wms.masterdata.identity.infrastructure;

import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.identity.domain.OwnerBindingDirectory;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.OwnerBindingMapper;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.OwnerBindingPO;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 货主绑定目录实现：只读 {@code wms_owner}，状态闭集交给 kernel 的 {@link OwnerStatus} 判定。
 *
 * <p>「是否可用」用 {@code OwnerStatus.valueOf(...).active()} 而不是在这里比字符串：
 * 两态与「可用」的口径只在 kernel 定义，身份模块只消费结论（不变量只在 kernel 定义）。
 */
@Repository
public class OwnerBindingDirectoryImpl implements OwnerBindingDirectory {

    private final OwnerBindingMapper mapper;

    public OwnerBindingDirectoryImpl(OwnerBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<OwnerBinding> find(long ownerId) {
        return Optional.ofNullable(mapper.find(ownerId)).map(this::toBinding);
    }

    @Override
    public List<OwnerBinding> find(Collection<Long> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return List.of();
        }
        return mapper.findByIds(ownerIds).stream().map(this::toBinding).toList();
    }

    private OwnerBinding toBinding(OwnerBindingPO po) {
        return new OwnerBinding(po.getOwnerId(), po.getOwnerNo(), po.getOwnerName(),
            OwnerStatus.valueOf(po.getStatus()).active());
    }
}
