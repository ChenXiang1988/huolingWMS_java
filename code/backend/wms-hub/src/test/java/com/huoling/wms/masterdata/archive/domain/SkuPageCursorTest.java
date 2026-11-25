package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuListSortKey;
import com.huoling.wms.masterdata.domain.SkuSortDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 键集游标：编码带排序字段与方向，换排序键后旧游标按参数错误拒绝，时间保留微秒。
 * 这三点合起来保证「按更新时间、编码、名称排序都能翻页且不重不漏」。
 */
class SkuPageCursorTest {

    private static final SkuListSort BY_TIME = SkuListSort.parse("updateTime", "desc");
    private static final SkuListSort BY_CODE = SkuListSort.parse("ownerCode", "asc");
    private static final SkuListSort BY_NAME = SkuListSort.parse("name", "desc");

    @Test
    @DisplayName("同一排序下往返一致，时间保留微秒不被截断")
    void 往返一致() {
        Instant time = Instant.parse("2026-11-25T03:04:05.123456Z");
        SkuPageCursor decoded = SkuPageCursor.decode(
            SkuPageCursor.ofTime(BY_TIME, time, 42L).encode(), BY_TIME);
        assertThat(decoded).isNotNull();
        assertThat(decoded.instantValue()).isEqualTo(time);
        assertThat(decoded.id()).isEqualTo(42L);
        assertThat(decoded.key()).isEqualTo(SkuListSortKey.UPDATE_TIME);
        assertThat(decoded.direction()).isEqualTo(SkuSortDirection.DESC);

        SkuPageCursor byCode = SkuPageCursor.decode(
            SkuPageCursor.ofText(BY_CODE, "OWNER-1", 7L).encode(), BY_CODE);
        assertThat(byCode.textValue()).isEqualTo("OWNER-1");
        assertThat(byCode.direction()).isEqualTo(SkuSortDirection.ASC);

        SkuPageCursor byName = SkuPageCursor.decode(
            SkuPageCursor.ofText(BY_NAME, "螺丝|M4", 8L).encode(), BY_NAME);
        assertThat(byName.textValue()).isEqualTo("螺丝|M4");
    }

    @Test
    @DisplayName("换排序字段或方向后旧游标按参数错误拒绝，不按另一种顺序续翻")
    void 排序变化即拒绝() {
        String cursor = SkuPageCursor.ofTime(BY_TIME, Instant.parse("2026-11-25T03:04:05Z"), 1L).encode();
        assertThatThrownBy(() -> SkuPageCursor.decode(cursor, BY_NAME))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
        assertThatThrownBy(() -> SkuPageCursor.decode(cursor, SkuListSort.parse("updateTime", "asc")))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("空游标表示第一页，形态不合法按参数错误拒绝")
    void 空值与坏形态() {
        assertThat(SkuPageCursor.decode(null, BY_TIME)).isNull();
        assertThat(SkuPageCursor.decode("  ", BY_TIME)).isNull();
        for (String plain : new String[]{"UPDATE_TIME|DESC|1", "BAD|DESC|1|x",
            "UPDATE_TIME|DESC|abc|x", "UPDATE_TIME|SIDEWAYS|1|x"}) {
            String illegal = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> SkuPageCursor.decode(illegal, BY_TIME))
                .as("cursor 明文=%s 应被拒绝", plain)
                .isInstanceOf(ArchiveException.class)
                .extracting(e -> ((ArchiveException) e).code())
                .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
        }
    }
}
