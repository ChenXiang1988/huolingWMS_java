package com.huoling.wms.masterdata.owner.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 货主数据域：空域不回落成全部、建档要求全域、域外按不存在（OI-08、INV-19）。 */
class OwnerScopeTest {

    @Test
    @DisplayName("全域覆盖任意货主，不判空")
    void 全域() {
        OwnerScope scope = OwnerScope.all();

        assertThat(scope.empty()).isFalse();
        assertThat(scope.covers(1L)).isTrue();
        assertThat(scope.covers(null)).isFalse();
        assertThatCode(scope::requireNotEmpty).doesNotThrowAnyException();
        assertThatCode(scope::requireAll).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("指定列表只覆盖列表内货主")
    void 指定列表() {
        OwnerScope scope = OwnerScope.of(Set.of(1L, 2L));

        assertThat(scope.empty()).isFalse();
        assertThat(scope.covers(1L)).isTrue();
        assertThat(scope.covers(9L)).isFalse();
    }

    @Test
    @DisplayName("空域直接拒绝，绝不回落成全部")
    void 空域拒绝() {
        OwnerScope empty = OwnerScope.of(Set.of());

        assertThat(empty.empty()).isTrue();
        assertThatThrownBy(empty::requireNotEmpty)
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
        assertThatThrownBy(empty::requireAll)
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
    }

    @Test
    @DisplayName("非全域账号不得建档")
    void 非全域不可建档() {
        OwnerScope list = OwnerScope.of(Set.of(1L));

        assertThatThrownBy(list::requireAll)
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
        // 读不受影响：指定列表账号可以查域内货主
        assertThatCode(list::requireNotEmpty).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("构造时拷贝并冻结集合，外部改动不影响已解析的域")
    void 集合不可变() {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>(Set.of(1L));
        OwnerScope scope = OwnerScope.of(ids);
        ids.add(2L);

        assertThat(scope.ownerIds()).containsExactly(1L);
        assertThatThrownBy(() -> scope.ownerIds().add(3L))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
