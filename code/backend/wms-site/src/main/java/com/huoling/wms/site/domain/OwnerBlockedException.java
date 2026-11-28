package com.huoling.wms.site.domain;

import com.huoling.wms.common.error.ArchiveContractCodes;

/**
 * 货主状态闸门的拒绝（tasks 1.14；design「失败模式与降级」四行）。
 *
 * <p>三种拒绝原因各自可辨，调用方（各单据域的受理入口）据此给文案与出口；
 * 错误码复用既有取值，不新增契约外错误码：
 *
 * <ul>
 *   <li>{@link Reason#OWNER_DISABLED}：副本明确停用 → {@code STATE_INVALID}（409，不可重试）。
 *       单据域接线时若已有更贴切的码，替换本类的码即可，判定与原因不变；</li>
 *   <li>{@link Reason#SNAPSHOT_MISSING} 与 {@link Reason#SNAPSHOT_STALE}：状态不可读或长期失联
 *       → {@code DEPENDENCY_UNAVAILABLE}（503，可重试，提示联系运营）。</li>
 * </ul>
 *
 * <p>为什么不给单据域造一个新码：dg 域的错误码属单据域契约（design「本变更只交付闸门」），
 * 在这里硬编码一个契约表里没有的码，等于用实现替契约做决定。
 */
public class OwnerBlockedException extends SiteException {

    /** 拒绝原因：三种情形在调用方看来是不同的处置。 */
    public enum Reason {
        /** 副本明确停用：以该货主为对象的新建业务被拒（存量单据与任务不受影响）。 */
        OWNER_DISABLED,
        /** 副本没有该货主的记录：新档尚未下行或消费链路断，状态不可读时不放行。 */
        SNAPSHOT_MISSING,
        /** 副本超过陈旧阈值：长期失联后副本不再可信。 */
        SNAPSHOT_STALE
    }

    /** 可重试拒绝的建议等待时间：与既有受理机制的 retryAfterMs 同一单位与口径。 */
    private static final long RETRY_AFTER_MS = 1000L;

    private final Reason reason;
    private final long ownerId;

    private OwnerBlockedException(Reason reason, long ownerId, String message) {
        super(codeOf(reason), message, reason == Reason.OWNER_DISABLED ? 0L : RETRY_AFTER_MS);
        this.reason = reason;
        this.ownerId = ownerId;
    }

    public static OwnerBlockedException disabled(long ownerId, String ownerNo) {
        return new OwnerBlockedException(Reason.OWNER_DISABLED, ownerId,
            "货主 " + ownerNo + " 已停用，不能新建业务；存量单据与任务继续执行至终态");
    }

    public static OwnerBlockedException snapshotMissing(long ownerId) {
        return new OwnerBlockedException(Reason.SNAPSHOT_MISSING, ownerId,
            "本仓尚未收到该货主的状态同步，暂不能受理新业务，请稍后重试或联系运营");
    }

    public static OwnerBlockedException snapshotStale(long ownerId, long staleSeconds) {
        return new OwnerBlockedException(Reason.SNAPSHOT_STALE, ownerId,
            "本仓货主状态已超过 " + staleSeconds + " 秒未同步，暂不能受理新业务，请联系运营核对");
    }

    private static String codeOf(Reason reason) {
        return reason == Reason.OWNER_DISABLED
            ? ArchiveContractCodes.STATE_INVALID
            : ArchiveContractCodes.DEPENDENCY_UNAVAILABLE;
    }

    public Reason reason() {
        return reason;
    }

    public long ownerId() {
        return ownerId;
    }
}
