package com.huoling.wms.masterdata.acceptance.domain;

import java.util.Set;

/**
 * 受理查询的可见范围：作用域、允许的接口号集合、目标货主集合，以及「查不到时用哪个错误码」。
 *
 * <p>为什么把接口号集合显式传进来（契约 1.6）：受理记录由两个业务共用一张表，
 * 每个业务只能看见自己的受理单。判据落在 SQL 谓词上而不是调用方自觉——
 * {@code api_code} 不在集合内即按不存在处理，不会把另一业务的受理单透出去。
 *
 * <p>为什么还要目标货主集合（{@code subjectOwnerIds}）：运营端的作用域键恒为 {@code HUB}，
 * 无法按货主域区分，货主档案的受理查询只能靠 {@code hub_archive_acceptance.subject_owner_id}
 * 收窄（design「幂等与异步受理」）。三态语义写死在这里，不留隐式回落：
 * <ul>
 *   <li>{@code null}：不按目标货主收窄（全域账号）；</li>
 *   <li>非空集合：只可见目标货主在集合内的受理单，集合外的与不存在同一响应；</li>
 *   <li>空集合：不允许查出任何记录（指定列表为空即空域，不回落成全量）。</li>
 * </ul>
 *
 * <p>为什么「查不到的错误码」也由调用方给：商品档案契约用的是 {@code NOT_FOUND_ARCHIVE}，
 * 货主档案契约用的是 {@code NOT_FOUND_OWNER}；机制不替业务选码。
 */
public record AcceptanceQuery(String scopeKey, Set<String> apiCodes, String notFoundCode,
                              Set<Long> subjectOwnerIds) {

    public AcceptanceQuery {
        apiCodes = apiCodes == null ? Set.of() : Set.copyOf(apiCodes);
        subjectOwnerIds = subjectOwnerIds == null ? null : Set.copyOf(subjectOwnerIds);
        if (notFoundCode == null || notFoundCode.isBlank()) {
            throw new IllegalArgumentException("受理查询必须给出查不到时的错误码");
        }
    }

    /** 不按目标货主收窄的查询（商品档案侧的既有口径）。 */
    public AcceptanceQuery(String scopeKey, Set<String> apiCodes, String notFoundCode) {
        this(scopeKey, apiCodes, notFoundCode, null);
    }
}
