package com.huoling.wms.masterdata.identity.domain;

import com.huoling.wms.common.domain.AggregateRoot;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 账号聚合。与员工一对一（employee_id 唯一），货主端账号 owner_id 非空、employee_id 为空（19）。
 */
@AggregateRoot
public class Account {

    private static final Pattern ACCOUNT_NAME = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");

    private final Long id;
    private final String accountName;
    private String passwordHash;
    private final Long employeeId;
    private final Long ownerId;
    private RecordStatus status;
    private final long version;

    private Account(Long id, String accountName, String passwordHash,
                    Long employeeId, Long ownerId, RecordStatus status, long version) {
        this.id = id;
        this.accountName = accountName;
        this.passwordHash = passwordHash;
        this.employeeId = employeeId;
        this.ownerId = ownerId;
        this.status = status;
        this.version = version;
    }

    public static Account create(Long id, String accountName, String passwordHash,
                                 Long employeeId, Long ownerId) {
        if (accountName == null || !ACCOUNT_NAME.matcher(accountName).matches()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST,
                "账号名只允许字母、数字与 . _ -，长度 3 到 64");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, "密码不能为空");
        }
        if (employeeId == null && ownerId == null) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST,
                "账号必须绑定员工（内部）或货主（货主端），两者不能同时为空");
        }
        if (employeeId != null && ownerId != null) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST,
                "账号不能同时绑定员工与货主");
        }
        return new Account(id, accountName, passwordHash, employeeId, ownerId, RecordStatus.ENABLED, 0);
    }

    public static Account restore(Long id, String accountName, String passwordHash,
                                  Long employeeId, Long ownerId, RecordStatus status, long version) {
        return new Account(id, accountName, passwordHash, employeeId, ownerId, status, version);
    }

    public void disable() {
        this.status = RecordStatus.DISABLED;
    }

    public void enable() {
        this.status = RecordStatus.ENABLED;
    }

    public void resetPassword(String newPasswordHash) {
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, "新密码不能为空");
        }
        this.passwordHash = newPasswordHash;
    }

    public boolean enabled() {
        return status == RecordStatus.ENABLED;
    }

    public boolean isInternal() {
        return employeeId != null;
    }

    /**
     * 账号所在端：绑定货主即货主端，否则运营端（{@code 05}「登录某端时只加载该端角色」）。
     *
     * <p>判定只看 {@code ownerId} 是否为空，不看员工/货主的类型分支（D03）：
     * 「绑定货主」是一个身份事实，不是「内外部货主」的分类。
     * 角色端标识比对、登录端点、令牌端标识三处都必须用同一个判定，
     * 否则会出现「角色挂在货主端、令牌发成运营端」这种半通不通的账号。
     */
    public ClientType client() {
        return ownerId != null ? ClientType.OWNER : ClientType.HUB;
    }

    public Long id() {
        return id;
    }

    public String accountName() {
        return accountName;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public Long employeeId() {
        return employeeId;
    }

    public Long ownerId() {
        return ownerId;
    }

    public RecordStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    /**
     * 乐观锁前置校验：客户端提交的版本必须是当前版本，否则返回 COMMON_9004（契约 API-009）。
     * 库表侧的最终判定由 {@code update ... where version = ?} 的受影响行数兜底。
     */
    public void assertVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new IdentityException(IdentityErrorCodes.COMMON_VERSION_CONFLICT,
                "账号已被修改，请刷新后重提");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
