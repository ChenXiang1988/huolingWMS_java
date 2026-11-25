package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;

import java.util.Locale;

/**
 * 审核动作的入口解析：动作闭集按接口区分，闭集外一律 {@code PARAM_INVALID}。
 *
 * <ul>
 *   <li>API-008 单条审核与 API-013 申请审核：只接受 {@code APPROVE}、{@code REJECT}（契约 API-008/013「请求」）；</li>
 *   <li>API-009 批量：接受 {@code APPROVE}、{@code REJECT}、{@code DISABLE}（契约 API-009「请求」）。</li>
 * </ul>
 *
 * <p>两个闭集分开而不是放宽同一个方法：把 {@code DISABLE} 放进单条审核的闭集，
 * 会让 API-008 接受契约外的动作；把批量闭集收窄成两项，则批量停用无处落地。
 */
final class ReviewActions {

    private ReviewActions() {
    }

    /** API-008、API-013 的动作闭集：只有通过与驳回。 */
    static SkuArchiveAction parseArchiveAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "字段 action 必填");
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> SkuArchiveAction.APPROVE;
            case "REJECT" -> SkuArchiveAction.REJECT;
            default -> throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "字段 action 只接受 APPROVE 或 REJECT，收到 " + raw);
        };
    }

    /**
     * API-009 的动作闭集：通过与驳回之外增加停用。
     *
     * <p>停用在批量里与通过、驳回一样逐条独立事务：状态机守卫仍是启用态才能停用，
     * 乐观锁仍是启用时的版本，原因必填由 Kernel 的停用规则判定。
     */
    static SkuArchiveAction parseBatchAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "字段 action 必填");
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> SkuArchiveAction.APPROVE;
            case "REJECT" -> SkuArchiveAction.REJECT;
            case "DISABLE" -> SkuArchiveAction.DISABLE;
            default -> throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "字段 action 只接受 APPROVE、REJECT 或 DISABLE，收到 " + raw);
        };
    }
}
