package com.huoling.wms.masterdata.identity.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.RecordStatus;
import com.huoling.wms.masterdata.identity.domain.Role;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.RoleMapper;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.RolePO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RoleRepositoryImpl implements RoleRepository {

    private final RoleMapper mapper;

    public RoleRepositoryImpl(RoleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Role> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<Role> findByCode(String roleCode) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<RolePO>()
            .eq(RolePO::getRoleCode, roleCode))).map(this::toDomain);
    }

    @Override
    public List<Role> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<RolePO>().orderByAsc(RolePO::getId))
            .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Role> findByClient(ClientType client) {
        return mapper.selectList(new LambdaQueryWrapper<RolePO>()
                .eq(RolePO::getAppliesToClient, client.name())
                .orderByAsc(RolePO::getId))
            .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Role> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectBatchIds(ids).stream().map(this::toDomain).toList();
    }

    @Override
    public void insert(Role role) {
        RolePO po = toPO(role);
        OffsetDateTime now = OffsetDateTime.now();
        po.setId(role.id() == null ? IdWorker.getId() : role.id());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        po.setCreatedBy(0L);
        po.setUpdatedBy(0L);
        mapper.insert(po);
    }

    @Override
    public void updateProfile(Long roleId, String roleName, RecordStatus status) {
        mapper.updateProfile(roleId, roleName, status.name());
    }

    @Override
    public List<Long> findRoleIdsByAccount(Long accountId) {
        return mapper.findRoleIdsByAccount(accountId);
    }

    @Override
    @Transactional
    public void replaceAccountRoles(Long accountId, List<Long> roleIds) {
        mapper.deleteAccountRoles(accountId);
        if (roleIds != null) {
            roleIds.stream().distinct().forEach(roleId -> mapper.insertAccountRole(accountId, roleId));
        }
    }

    @Override
    public List<String> findPermissionCodesByRole(Long roleId) {
        return mapper.findPermissionCodesByRole(roleId);
    }

    @Override
    public boolean advanceVersion(Long roleId, long expectedVersion) {
        return mapper.advanceVersion(roleId, expectedVersion) == 1;
    }

    @Override
    @Transactional
    public void replaceRolePermissions(Long roleId, List<String> permissionCodes) {
        mapper.deleteRolePermissions(roleId);
        if (permissionCodes != null) {
            permissionCodes.stream().distinct()
                .forEach(code -> mapper.insertRolePermission(roleId, code));
        }
    }

    @Override
    public boolean hasAnyPermissions(Long roleId) {
        return mapper.countRolePermissions(roleId) > 0;
    }

    @Override
    @Transactional
    public int addPermissionsIfAbsent(Long roleId, List<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (String code : permissionCodes) {
            inserted += mapper.insertRolePermissionIfAbsent(roleId, code);
        }
        return inserted;
    }

    private Role toDomain(RolePO po) {
        return Role.restore(po.getId(), po.getRoleCode(), po.getRoleName(),
            ClientType.valueOf(po.getAppliesToClient()),
            RecordStatus.valueOf(po.getStatus()),
            po.getVersion() == null ? 0 : po.getVersion());
    }

    private RolePO toPO(Role role) {
        RolePO po = new RolePO();
        po.setId(role.id());
        po.setRoleCode(role.roleCode());
        po.setRoleName(role.roleName());
        po.setAppliesToClient(role.appliesToClient().name());
        po.setStatus(role.status().name());
        po.setVersion(role.version());
        return po;
    }
}
