package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.acceptance.domain.AcceptanceQuery;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 商品档案受理查询的可见范围（契约「写接口统一响应」与 1.6 的接口隔离）。
 *
 * <p>受理记录由商品档案与货主档案共用一张表，商品档案的受理查询只能看见自己的单子：
 * 谓词取「{@code api_code} 落在商品档案既有接口号区间（API-001 至 API-020）」，
 * 货主档案（API-023 至 API-026）的受理单在这里查不到，按不存在同一响应返回。
 *
 * <p>作用域口径不变：运营端为全量视图（{@code scopeKey} 为空），货主端取登录账号的归属货主。
 */
public final class ArchiveAcceptanceQueries {

    private static final String API_CODE_FORMAT = "API-%03d";
    private static final int FIRST_ARCHIVE_API = 1;
    private static final int LAST_ARCHIVE_API = 20;

    private ArchiveAcceptanceQueries() {
    }

    /** 运营端：全量视图，限商品档案接口号区间。 */
    public static AcceptanceQuery hub() {
        return new AcceptanceQuery(null, apiCodes(), ArchiveErrorCodes.NOT_FOUND_ARCHIVE);
    }

    /** 货主端：归属货主作用域，限商品档案接口号区间；跨货主查询按不存在拒绝。 */
    public static AcceptanceQuery owner(Long ownerId) {
        return new AcceptanceQuery(String.valueOf(ownerId), apiCodes(), ArchiveErrorCodes.NOT_FOUND_ARCHIVE);
    }

    private static Set<String> apiCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (int code = FIRST_ARCHIVE_API; code <= LAST_ARCHIVE_API; code++) {
            codes.add(String.format(API_CODE_FORMAT, code));
        }
        return Set.copyOf(codes);
    }
}
