package com.huoling.wms.masterdata.identity.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.identity.domain.RoleScope;
import com.huoling.wms.masterdata.identity.domain.RoleScopeRepository;
import com.huoling.wms.masterdata.identity.domain.ScopeDimension;
import com.huoling.wms.masterdata.identity.domain.ScopeType;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.RoleScopeMapper;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.RoleScopePO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Repository
public class RoleScopeRepositoryImpl implements RoleScopeRepository {

    private final RoleScopeMapper mapper;

    public RoleScopeRepositoryImpl(RoleScopeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<RoleScope> findByRole(Long roleId) {
        return mapper.findByRole(roleId).stream()
            .map(po -> RoleScope.define(po.getId(), po.getRoleId(),
                ScopeDimension.valueOf(po.getDimension()),
                ScopeType.valueOf(po.getScopeType()),
                new LinkedHashSet<>(mapper.findTargetIds(po.getId()))))
            .toList();
    }

    @Override
    @Transactional
    public void replaceAll(Long roleId, List<RoleScope> scopes) {
        List<RoleScopePO> existing = mapper.findByRole(roleId);
        existing.forEach(po -> mapper.deleteItems(po.getId()));
        mapper.deleteByRole(roleId);
        for (RoleScope scope : scopes) {
            RoleScopePO po = new RoleScopePO();
            long scopeId = IdWorker.getId();
            po.setId(scopeId);
            po.setRoleId(roleId);
            po.setDimension(scope.dimension().name());
            po.setScopeType(scope.scopeType().name());
            mapper.insert(po);
            for (Long targetId : scope.targetIds()) {
                mapper.insertItem(IdWorker.getId(), scopeId, targetId);
            }
        }
    }
}
