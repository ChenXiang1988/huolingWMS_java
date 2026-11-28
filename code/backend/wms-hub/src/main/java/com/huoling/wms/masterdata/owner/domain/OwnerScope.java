package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.common.domain.ValueObject;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 货主档案的数据域（INV-19 的第二层、第三道门）。
 *
 * <p>货主档案与其他主数据不同：表本身既是被查主体，又是隔离维度，所以「注入 {@code owner_id}」
 * 在这里退化为「注入被查对象集合 {@code id}」——全域时不加条件，指定列表时加 {@code id in (...)}。
 *
 * <p>两条硬口径写在这里，仓储与应用服务都调用，不各写一套：
 * <ul>
 *   <li>域为空直接 {@code FORBIDDEN_OWNER}，MUST NOT 回落成「全部」（OI-08、`05` 第 3 节）；</li>
 *   <li>建档要求域为「全部」：新建对象无法落入既有指定列表（OI-08）。</li>
 * </ul>
 */
@ValueObject
public record OwnerScope(boolean allOwners, Set<Long> ownerIds) {

    public OwnerScope {
        ownerIds = Collections.unmodifiableSet(
            ownerIds == null ? Set.of() : new LinkedHashSet<>(ownerIds));
    }

    /** 全域：未配置域或任一角色为全部。 */
    public static OwnerScope all() {
        return new OwnerScope(true, Set.of());
    }

    /** 指定列表；列表为空按空域处理，由 {@link #requireNotEmpty()} 拒绝。 */
    public static OwnerScope of(Collection<Long> ownerIds) {
        return new OwnerScope(false, ownerIds == null ? Set.of() : Set.copyOf(ownerIds));
    }

    /** 是否空域：指定列表且没有任何标识。 */
    public boolean empty() {
        return !allOwners && ownerIds.isEmpty();
    }

    /** 是否覆盖给定货主。 */
    public boolean covers(Long ownerId) {
        return ownerId != null && (allOwners || ownerIds.contains(ownerId));
    }

    /**
     * 空域直接拒绝，不回落成全部。
     *
     * @throws OwnerContractException 空域为 {@code FORBIDDEN_OWNER}
     */
    public void requireNotEmpty() {
        if (empty()) {
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER,
                "货主数据域为空，拒绝访问");
        }
    }

    /**
     * 建档必须全域。
     *
     * @throws OwnerContractException 指定列表为 {@code FORBIDDEN_OWNER}
     */
    public void requireAll() {
        requireNotEmpty();
        if (!allOwners) {
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER,
                "新建货主档案要求全域数据域，指定列表账号不得建档");
        }
    }
}
