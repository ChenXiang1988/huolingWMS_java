package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerListSortKey;
import com.huoling.wms.masterdata.domain.OwnerSortDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 键集分页游标：与排序参数绑定、时间不截断、字段与方向解码后原样可用（契约 1.1「分页」）。 */
class OwnerPageCursorTest {

    @Test
    @DisplayName("编号排序：编码后可解码，锚点值即货主编号")
    void 编号排序往返() {
        OwnerListSort sort = OwnerListSort.parse("ownerNo", "asc");
        OwnerPageCursor cursor = OwnerPageCursor.ofText(sort, "OW000009", "OW000009");

        OwnerPageCursor decoded = OwnerPageCursor.decode(cursor.encode(), sort);

        assertThat(decoded.key()).isEqualTo(OwnerListSortKey.OWNER_NO);
        assertThat(decoded.direction()).isEqualTo(OwnerSortDirection.ASC);
        assertThat(decoded.textValue()).isEqualTo("OW000009");
        assertThat(decoded.ownerNo()).isEqualTo("OW000009");
    }

    @Test
    @DisplayName("名称排序：值里含分隔符也不串段")
    void 名称排序含分隔符() {
        OwnerListSort sort = OwnerListSort.parse("ownerName", "desc");
        OwnerPageCursor cursor = OwnerPageCursor.ofText(sort, "华东|仓储有限公司", "OW000012");

        OwnerPageCursor decoded = OwnerPageCursor.decode(cursor.encode(), sort);

        assertThat(decoded.textValue()).isEqualTo("华东|仓储有限公司");
        assertThat(decoded.ownerNo()).isEqualTo("OW000012");
        assertThat(decoded.direction()).isEqualTo(OwnerSortDirection.DESC);
    }

    @Test
    @DisplayName("时间排序：ISO-8601 原样编码，微秒不丢")
    void 时间排序保留微秒() {
        OwnerListSort sort = OwnerListSort.parse("updateTime", "desc");
        Instant time = Instant.parse("2026-11-25T08:30:00.123456Z");

        OwnerPageCursor decoded = OwnerPageCursor.decode(
            OwnerPageCursor.ofTime(sort, time, "OW000001").encode(), sort);

        assertThat(decoded.instantValue()).isEqualTo(time);
    }

    @Test
    @DisplayName("空游标表示第一页")
    void 空游标() {
        assertThat(OwnerPageCursor.decode(null, OwnerListSort.parse(null, null))).isNull();
        assertThat(OwnerPageCursor.decode("  ", OwnerListSort.parse(null, null))).isNull();
    }

    @Test
    @DisplayName("游标与排序参数不一致时拒绝，不按另一种顺序续翻")
    void 排序不一致拒绝() {
        OwnerListSort byNo = OwnerListSort.parse("ownerNo", "asc");
        String encoded = OwnerPageCursor.ofText(byNo, "OW000009", "OW000009").encode();

        assertThatThrownBy(() -> OwnerPageCursor.decode(encoded,
            OwnerListSort.parse("updateTime", "asc")))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.PARAM_INVALID);
        assertThatThrownBy(() -> OwnerPageCursor.decode(encoded,
            OwnerListSort.parse("ownerNo", "desc")))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("形态不合法按参数错误拒绝")
    void 形态不合法() {
        OwnerListSort sort = OwnerListSort.parse(null, null);

        assertThatThrownBy(() -> OwnerPageCursor.decode("not-base64!!", sort))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.PARAM_INVALID);
        assertThatThrownBy(() -> OwnerPageCursor.decode("QUJD", sort))
            .as("段数不足").isInstanceOf(OwnerContractException.class);
    }
}
