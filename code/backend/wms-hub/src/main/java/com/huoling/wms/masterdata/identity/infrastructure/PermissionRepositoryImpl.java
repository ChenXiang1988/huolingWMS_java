package com.huoling.wms.masterdata.identity.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.Permission;
import com.huoling.wms.masterdata.identity.domain.PermissionRepository;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.PermissionMapper;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.PermissionPO;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class PermissionRepositoryImpl implements PermissionRepository {

    private final PermissionMapper mapper;

    public PermissionRepositoryImpl(PermissionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Permission> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<PermissionPO>().orderByAsc(PermissionPO::getPermCode))
            .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Permission> findAllEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<PermissionPO>()
                .eq(PermissionPO::getStatus, RecordStatus.ENABLED.name())
                .orderByAsc(PermissionPO::getPermCode))
            .stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<Permission> findByCode(String permCode) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<PermissionPO>()
            .eq(PermissionPO::getPermCode, permCode))).map(this::toDomain);
    }

    @Override
    public Set<String> findCodesByAccount(Long accountId, ClientType client) {
        return new HashSet<>(mapper.findCodesByAccount(accountId, client.name()));
    }

    @Override
    public void insert(Permission permission) {
        PermissionPO po = new PermissionPO();
        OffsetDateTime now = OffsetDateTime.now();
        po.setId(permission.id() == null ? IdWorker.getId() : permission.id());
        po.setPermCode(permission.permCode());
        po.setModule(permission.module());
        po.setStatus(permission.status().name());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        mapper.insert(po);
    }

    @Override
    public void updateStatus(Long id, RecordStatus status) {
        PermissionPO po = new PermissionPO();
        po.setId(id);
        po.setStatus(status.name());
        po.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(po);
    }

    @Override
    public void updateModule(Long id, String module) {
        PermissionPO po = new PermissionPO();
        po.setId(id);
        po.setModule(module);
        po.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(po);
    }

    private Permission toDomain(PermissionPO po) {
        return Permission.restore(po.getId(), po.getPermCode(), po.getModule(),
            RecordStatus.valueOf(po.getStatus()));
    }
}
