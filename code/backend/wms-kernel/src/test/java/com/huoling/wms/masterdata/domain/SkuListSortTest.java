package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排序参数闭集：{@code sortBy} 只接受 updateTime、ownerCode、name，{@code sortDir} 只接受 asc、desc；
 * 缺省为更新时间降序，闭集外一律参数错误（契约字段结构表 sortBy、sortDir 与「枚举约定」）。
 */
class SkuListSortTest {

    @Test
    @DisplayName("缺省按更新时间降序")
    void 缺省值() {
        assertThat(SkuListSort.parse(null, null))
            .isEqualTo(new SkuListSort(SkuListSortKey.UPDATE_TIME, SkuSortDirection.DESC));
        assertThat(SkuListSort.parse("  ", " ").direction()).isEqualTo(SkuSortDirection.DESC);
    }

    @Test
    @DisplayName("闭集内的三个字段与两个方向都能解析")
    void 闭集内取值() {
        for (SkuListSortKey key : SkuListSortKey.values()) {
            for (SkuSortDirection direction : SkuSortDirection.values()) {
                SkuListSort sort = SkuListSort.parse(key.wireName(), direction.wireName());
                assertThat(sort.key()).isEqualTo(key);
                assertThat(sort.direction()).isEqualTo(direction);
            }
        }
    }

    @Test
    @DisplayName("升序标记与方向一致")
    void 升序标记() {
        assertThat(SkuListSort.parse("name", "asc").ascending()).isTrue();
        assertThat(SkuListSort.parse("name", "desc").ascending()).isFalse();
    }

    @Test
    @DisplayName("首尾空白与大小写不影响解析，闭集外取值一律参数错误")
    void 非法取值() {
        assertThat(SkuListSort.parse(" UpdateTime ", " ASC ").key()).isEqualTo(SkuListSortKey.UPDATE_TIME);
        assertThat(SkuListSort.parse("ownerCode", "Asc").direction()).isEqualTo(SkuSortDirection.ASC);
        for (String illegal : new String[]{"status", "createdAt", "update_time", "owner", "1"}) {
            assertThatThrownBy(() -> SkuListSort.parse(illegal, null))
                .as("sortBy=%s 应被拒绝", illegal)
                .isInstanceOf(SkuRuleViolation.class)
                .extracting(e -> ((SkuRuleViolation) e).code())
                .isEqualTo(SkuRuleCodes.PARAM_INVALID);
        }
        for (String illegal : new String[]{"ascending", "up", "0", "desc1"}) {
            assertThatThrownBy(() -> SkuListSort.parse(null, illegal))
                .as("sortDir=%s 应被拒绝", illegal)
                .isInstanceOf(SkuRuleViolation.class)
                .extracting(e -> ((SkuRuleViolation) e).code())
                .isEqualTo(SkuRuleCodes.PARAM_INVALID);
        }
    }
}
