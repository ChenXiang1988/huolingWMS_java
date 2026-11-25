package com.huoling.wms.masterdata.identity.domain;

import com.huoling.wms.common.domain.AggregateRoot;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 角色聚合。角色编码发布后不可改，名称与状态可改，端标识不可改（05）。
 */
@AggregateRoot
public class Role {

    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,47}$");

    private final Long id;
    private final String roleCode;
    private String roleName;
    private final ClientType appliesToClient;
    private RecordStatus status;
    private final long version;

    private Role(Long id, String roleCode, String roleName,
                 ClientType appliesToClient, RecordStatus status, long version) {
        this.id = id;
        this.roleCode = roleCode;
        this.roleName = roleName;
        this.appliesToClient = appliesToClient;
        this.status = status;
        this.version = version;
    }

    public static Role create(Long id, String roleCode, String roleName, ClientType client) {
        if (roleCode == null || !ROLE_CODE.matcher(roleCode).matches()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST,
                "角色编码只允许大写字母、数字与下划线，长度 3 到 48");
        }
        if (roleName == null || roleName.isBlank()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, "角色名称不能为空");
        }
        if (client == null) {
            throw new IdentityException(IdentityErrorCodes.ROLE_CLIENT_MISMATCH, "角色必须归属某个端");
        }
        return new Role(id, roleCode, roleName, client, RecordStatus.ENABLED, 0);
    }

    public static Role restore(Long id, String roleCode, String roleName,
                               ClientType client, RecordStatus status, long version) {
        return new Role(id, roleCode, roleName, client, status, version);
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, "角色名称不能为空");
        }
        this.roleName = newName;
    }

    public void disable() {
        this.status = RecordStatus.DISABLED;
    }

    public void enable() {
        this.status = RecordStatus.ENABLED;
    }

    public boolean belongsTo(ClientType client) {
        return appliesToClient == client;
    }

    public boolean enabled() {
        return status == RecordStatus.ENABLED;
    }

    public Long id() {
        return id;
    }

    public String roleCode() {
        return roleCode;
    }

    public String roleName() {
        return roleName;
    }

    public ClientType appliesToClient() {
        return appliesToClient;
    }

    public RecordStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    /**
     * 乐观锁前置校验：客户端提交的版本必须是当前版本，否则返回 COMMON_9004（契约 API-013、API-016）。
     * 库表侧的最终判定由 {@code update ... where version = ?} 的受影响行数兜底。
     */
    public void assertVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new IdentityException(IdentityErrorCodes.COMMON_VERSION_CONFLICT,
                "角色已被修改，请刷新后重提");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Role other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
