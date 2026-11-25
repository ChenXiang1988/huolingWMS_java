package com.huoling.wms.masterdata.identity.domain;

import com.huoling.wms.common.domain.AggregateRoot;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 权限点聚合。权限点由代码声明并同步，不可手工新增，只能置停用（19）。
 */
@AggregateRoot
public class Permission {

    private static final Pattern PERM_CODE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*){2,4}$");

    private final Long id;
    private final String permCode;
    private final String module;
    private RecordStatus status;

    private Permission(Long id, String permCode, String module, RecordStatus status) {
        this.id = id;
        this.permCode = permCode;
        this.module = module;
        this.status = status;
    }

    public static Permission declare(Long id, String permCode, String module) {
        if (permCode == null || !PERM_CODE.matcher(permCode).matches()) {
            throw new IdentityException(IdentityErrorCodes.PERM_UNKNOWN,
                "权限点编码必须形如 hub.account.read");
        }
        if (module == null || module.isBlank()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, "权限点必须归属某个模块");
        }
        return new Permission(id, permCode, module, RecordStatus.ENABLED);
    }

    public static Permission restore(Long id, String permCode, String module, RecordStatus status) {
        return new Permission(id, permCode, module, status);
    }

    public void disable() {
        this.status = RecordStatus.DISABLED;
    }

    public void enable() {
        this.status = RecordStatus.ENABLED;
    }

    public boolean enabled() {
        return status == RecordStatus.ENABLED;
    }

    public Long id() {
        return id;
    }

    public String permCode() {
        return permCode;
    }

    public String module() {
        return module;
    }

    public RecordStatus status() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Permission other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
