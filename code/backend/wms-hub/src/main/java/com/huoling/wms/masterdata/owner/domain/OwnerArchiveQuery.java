package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerStatus;

import java.time.Instant;

/**
 * 货主档案列表查询条件（契约 API-021「请求」）。
 *
 * <p>{@code scope} 必填：货主档案表既是被查主体又是隔离维度，仓储层按它拼
 * {@code id in (...)} 或无条件（INV-19、OI-08）；业务代码不手写隔离条件，
 * 也不允许用「空列表等于全部」的隐式回落。
 *
 * <p>四类筛选（编号精确、名称模糊、信用代码精确、状态）加最后修改时间闭区间，
 * 组合取交集；{@code sort} 决定排序与游标锚点，换排序键后旧游标即失效
 * （{@link OwnerPageCursor} 编码里带着字段与方向）。
 */
public record OwnerArchiveQuery(
        OwnerScope scope,
        String ownerNo,
        String ownerName,
        String creditCode,
        OwnerStatus status,
        Instant updatedFrom,
        Instant updatedTo,
        OwnerListSort sort,
        OwnerPageCursor cursor,
        int limit) {

    /** 契约缺省每页条数。 */
    public static final int DEFAULT_LIMIT = 20;
    /** 契约单页上限。 */
    public static final int MAX_LIMIT = 100;

    public OwnerArchiveQuery {
        if (scope == null) {
            // 缺域一律越权：不把「没有域」当成「全部」（OI-08）。
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER, "缺少货主数据域，拒绝查询");
        }
        scope.requireNotEmpty();
        // 外部入参上限一百由接口层校验；这里多放一条，供查询服务「多取一条判定是否有下一页」。
        if (limit <= 0 || limit > MAX_LIMIT + 1) {
            throw new OwnerContractException(OwnerErrorCodes.PARAM_INVALID,
                "参数 limit 取值 1 到 " + MAX_LIMIT);
        }
        if (updatedFrom != null && updatedTo != null && updatedFrom.isAfter(updatedTo)) {
            throw new OwnerContractException(OwnerErrorCodes.PARAM_INVALID,
                "参数 updatedFrom 不得晚于 updatedTo");
        }
        if (sort == null) {
            sort = OwnerListSort.parse(null, null);
        }
    }
}
