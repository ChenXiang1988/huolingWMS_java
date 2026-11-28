package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.acceptance.application.AcceptanceService;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceQuery;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 货主档案受理单的接口号集合与查询范围（契约 API-023 至 API-027、1.6「跨接口约束」）。
 *
 * <p>受理记录与商品档案共用一张表，两个业务互相看不见：
 * <ul>
 *   <li>货主档案的受理查询只带 {@code api_code in ('API-023'..'API-026')}，
 *       商品档案的受理单在这里按不存在处理；</li>
 *   <li>反过来，商品档案的受理查询限定 001 至 020，货主档案的受理单不会经它暴露。</li>
 * </ul>
 *
 * <p>数据域收窄：域为「全部」不按目标货主收窄；域为「指定列表」只可见目标货主在域内的受理单
 * （{@code subject_owner_id in (...)}，建档受理单该列为空，对列表域账号不可见）；
 * 域为空在入口就拒绝，不走到这里（OI-08）。
 */
public final class OwnerAcceptanceQueries {

    /** API-023 建档。 */
    public static final String API_CREATE = "API-023";
    /** API-024 改档。 */
    public static final String API_EDIT = "API-024";
    /** API-025 停用。 */
    public static final String API_DISABLE = "API-025";
    /** API-026 启用。 */
    public static final String API_ENABLE = "API-026";

    /** 有序集合：受理积压的 SQL 谓词按固定顺序生成，便于对账日志比对。 */
    private static final Set<String> API_CODES = Collections.unmodifiableSet(
        new LinkedHashSet<>(List.of(API_CREATE, API_EDIT, API_DISABLE, API_ENABLE)));

    private OwnerAcceptanceQueries() {
    }

    /** 本业务的受理接口号，受理积压指标按它统计。 */
    public static Set<String> apiCodes() {
        return API_CODES;
    }

    /**
     * 按会话货主域构造受理查询范围。
     *
     * @throws OwnerContractException 域为空时 {@code FORBIDDEN_OWNER}（不回落成全量）
     */
    public static AcceptanceQuery of(OwnerScope scope) {
        if (scope == null) {
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER,
                "缺少货主数据域，拒绝查询受理结果");
        }
        scope.requireNotEmpty();
        return new AcceptanceQuery(AcceptanceService.SCOPE_HUB, API_CODES,
            OwnerErrorCodes.NOT_FOUND_OWNER, scope.allOwners() ? null : scope.ownerIds());
    }
}
