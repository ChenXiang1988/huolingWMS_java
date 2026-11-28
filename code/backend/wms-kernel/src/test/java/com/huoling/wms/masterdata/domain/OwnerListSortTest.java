package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 货主档案列表的排序白名单与缺省值（契约 1.1「排序与筛选」、API-020 与 API-021 的请求行）。 */
class OwnerListSortTest {

    @Test
    @DisplayName("都缺省时按货主编号升序")
    void 缺省值() {
        OwnerListSort sort = OwnerListSort.parse(null, null);

        assertThat(sort.key()).isEqualTo(OwnerListSortKey.OWNER_NO);
        assertThat(sort.direction()).isEqualTo(OwnerSortDirection.ASC);
        assertThat(sort.ascending()).isTrue();
    }

    @Test
    @DisplayName("白名单三项按契约英文名解析，方向不区分大小写")
    void 白名单取值() {
        assertThat(OwnerListSort.parse("ownerName", "desc").key())
            .isEqualTo(OwnerListSortKey.OWNER_NAME);
        assertThat(OwnerListSort.parse("updateTime", "DESC").ascending()).isFalse();
        assertThat(OwnerListSort.parse("ownerNo", "asc").key())
            .isEqualTo(OwnerListSortKey.OWNER_NO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"creditCode", "status", "name", "ownerCode"})
    @DisplayName("白名单外取值按参数错误拒绝，不静默回落成缺省排序")
    void 白名单外拒绝(String sortBy) {
        assertThatThrownBy(() -> OwnerListSort.parse(sortBy, null))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"up", "ascending", "1"})
    @DisplayName("方向闭集外取值按参数错误拒绝")
    void 方向闭集外拒绝(String sortDir) {
        assertThatThrownBy(() -> OwnerListSort.parse("ownerNo", sortDir))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }
}
