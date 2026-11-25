package com.huoling.wms.masterdata.archive.domain;

import java.time.Instant;

/**
 * 幂等判定（契约「幂等约定」的判定顺序，纯函数，便于单测覆盖四种出口）。
 *
 * <p>顺序固定：先看记录是否在窗口内 → 再比对指纹 → 再看状态。
 * 指纹比对放在状态之前，保证「同键异体」永远是冲突，而不是被处理中或首次结果掩盖。
 */
public final class IdempotencyDecision {

    /** 判定出口。 */
    public enum Outcome {
        /** 无记录或记录已过期：占位并执行业务。 */
        PROCEED,
        /** 同键同指纹且已成功：回放首次受理结果。 */
        REPLAY,
        /** 同键异体：冲突，首次结果不变。 */
        CONFLICT,
        /** 同键同指纹但业务仍在处理：可重试错误。 */
        IN_PROGRESS
    }

    private IdempotencyDecision() {
    }

    public static Outcome decide(IdempotencyRecord record, String fingerprint, Instant now) {
        if (record == null) {
            return Outcome.PROCEED;
        }
        if (record.expiresAt() != null && now != null && !record.expiresAt().isAfter(now)) {
            // 窗口到期：同键可重新受理，旧结果不再回放。
            return Outcome.PROCEED;
        }
        if (record.fingerprint() == null || !record.fingerprint().equals(fingerprint)) {
            return Outcome.CONFLICT;
        }
        return switch (record.status()) {
            case SUCCEEDED -> Outcome.REPLAY;
            case IN_PROGRESS -> Outcome.IN_PROGRESS;
            case FAILED -> Outcome.PROCEED;
        };
    }
}
