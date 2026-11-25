package com.huoling.wms.site.domain;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单仓端键集游标：编码带排序字段与方向，换排序键后旧游标按参数错误拒绝。
 * 保证本仓档案与本仓申请两类列表按任一排序字段都能不重不漏地翻页。
 */
class SitePageCursorTest {

    private static final SkuListSort BY_TIME = SkuListSort.parse("updateTime", "desc");
    private static final SkuListSort BY_CODE = SkuListSort.parse("ownerCode", "asc");

    @Test
    @DisplayName("同一排序下往返一致，时间保留微秒")
    void 往返一致() {
        Instant time = Instant.parse("2026-11-25T03:04:05.123456Z");
        SitePageCursor decoded = SitePageCursor.decode(
            SitePageCursor.ofTime(BY_TIME, time, 9L).encode(), BY_TIME);
        assertThat(decoded).isNotNull();
        assertThat(decoded.instantValue()).isEqualTo(time);
        assertThat(decoded.id()).isEqualTo(9L);

        SitePageCursor byCode = SitePageCursor.decode(
            SitePageCursor.ofText(BY_CODE, "OWNER-9", 3L).encode(), BY_CODE);
        assertThat(byCode.textValue()).isEqualTo("OWNER-9");
    }

    @Test
    @DisplayName("换排序字段或方向后旧游标按参数错误拒绝")
    void 排序变化即拒绝() {
        String cursor = SitePageCursor.ofTime(BY_TIME, Instant.parse("2026-11-25T03:04:05Z"), 1L).encode();
        for (SkuListSort other : new SkuListSort[]{
            SkuListSort.parse("name", "desc"), SkuListSort.parse("updateTime", "asc")}) {
            assertThatThrownBy(() -> SitePageCursor.decode(cursor, other))
                .isInstanceOf(SiteException.class)
                .extracting(e -> ((SiteException) e).code())
                .isEqualTo(ArchiveContractCodes.PARAM_INVALID);
        }
    }

    @Test
    @DisplayName("空游标表示第一页，形态不合法按参数错误拒绝")
    void 空值与坏形态() {
        assertThat(SitePageCursor.decode(null, BY_TIME)).isNull();
        assertThatThrownBy(() -> SitePageCursor.decode("Zm9v", BY_TIME))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.PARAM_INVALID);
    }
}
