package com.huoling.wms.masterdata.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 端间一致性规则：单仓缓存按版本号去重，落后版本的摘要必须丢弃；
 * 缓存数据超过新鲜度阈值即标记待刷新，展示时带数据时间戳（本次变更 design「幂等与异步事件」）。
 */
public final class SkuSnapshotRule {

    private SkuSnapshotRule() {
    }

    /**
     * 摘要是否应当被应用。
     *
     * @param cachedVersion   本地已应用的版本号，未缓存过时为负数或零
     * @param incomingVersion 下行摘要携带的版本号
     * @return 仅当摘要版本严格大于已应用版本时返回 {@code true}
     */
    public static boolean shouldApply(long cachedVersion, long incomingVersion) {
        if (incomingVersion <= 0) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "下行摘要缺少版本号");
        }
        return incomingVersion > cachedVersion;
    }

    /**
     * 缓存是否待刷新。
     *
     * @param dataTimestamp 缓存数据的产生时间（UTC）
     * @param now           当前时间
     * @param maxAge        新鲜度阈值
     */
    public static boolean stale(Instant dataTimestamp, Instant now, Duration maxAge) {
        if (dataTimestamp == null) {
            return true;
        }
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "新鲜度阈值必须为正");
        }
        return dataTimestamp.plus(maxAge).isBefore(now == null ? Instant.now() : now);
    }
}
