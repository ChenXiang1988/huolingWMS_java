package com.huoling.wms.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主内判重：编码与条码各自唯一，草稿与停用不参与，自身不与自己冲突。
 * 跨货主的隔离由仓储层注入 owner_id 保证，这里只判定候选集内部。
 */
class SkuArchiveDedupRuleTest {

    private static SkuArchiveDedupRule.SkuIdentity identity(Long id, SkuArchiveStatus status,
                                                            String code, String barcode) {
        return new SkuArchiveDedupRule.SkuIdentity(id, status, code, barcode);
    }

    @Test
    @DisplayName("同货主同编码被拒绝，错误码 RULE_DUPLICATE_CODE")
    void 编码重复() {
        assertThatThrownBy(() -> SkuArchiveDedupRule.requireUnique(null, "SKU-001", "6901234567890",
            List.of(identity(9L, SkuArchiveStatus.ACTIVE, "SKU-001", "6901234567891"))))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.RULE_DUPLICATE_CODE);
    }

    @Test
    @DisplayName("同货主同条码被拒绝，错误码 RULE_DUPLICATE_BARCODE")
    void 条码重复() {
        assertThatThrownBy(() -> SkuArchiveDedupRule.requireUnique(null, "SKU-002", "6901234567890",
            List.of(identity(9L, SkuArchiveStatus.PENDING, "SKU-001", "6901234567890"))))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(SkuRuleCodes.RULE_DUPLICATE_BARCODE);
    }

    @Test
    @DisplayName("草稿态竞争者不参与判重，草稿之间允许重复")
    void 草稿不参与判重() {
        assertThatCode(() -> SkuArchiveDedupRule.requireUnique(null, "SKU-001", "6901234567890",
            List.of(identity(9L, SkuArchiveStatus.DRAFT, "SKU-001", "6901234567890"))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("停用后同编码可被新草稿使用")
    void 停用不参与判重() {
        assertThatCode(() -> SkuArchiveDedupRule.requireUnique(null, "SKU-001", "6901234567890",
            List.of(identity(9L, SkuArchiveStatus.DISABLED, "SKU-001", "6901234567890"))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("驳回态仍占用编码，需编辑后重提不能换号抢占")
    void 驳回态参与判重() {
        assertThatThrownBy(() -> SkuArchiveDedupRule.requireUnique(null, "SKU-001", "6901234567890",
            List.of(identity(9L, SkuArchiveStatus.REJECTED, "SKU-001", "6901234567891"))))
            .isInstanceOf(SkuRuleViolation.class);
    }

    @Test
    @DisplayName("自身不与自己冲突，重新提交不会因自己失败")
    void 排除自身() {
        assertThatCode(() -> SkuArchiveDedupRule.requireUnique(9L, "SKU-001", "6901234567890",
            List.of(identity(9L, SkuArchiveStatus.REJECTED, "SKU-001", "6901234567890"))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("判重范围：草稿与停用不参与，其余参与")
    void 判重范围() {
        assertThat(SkuArchiveDedupRule.countsInUniqueness(SkuArchiveStatus.DRAFT)).isFalse();
        assertThat(SkuArchiveDedupRule.countsInUniqueness(SkuArchiveStatus.DISABLED)).isFalse();
        assertThat(SkuArchiveDedupRule.countsInUniqueness(SkuArchiveStatus.PENDING)).isTrue();
        assertThat(SkuArchiveDedupRule.countsInUniqueness(SkuArchiveStatus.ACTIVE)).isTrue();
        assertThat(SkuArchiveDedupRule.countsInUniqueness(SkuArchiveStatus.REJECTED)).isTrue();
    }
}
