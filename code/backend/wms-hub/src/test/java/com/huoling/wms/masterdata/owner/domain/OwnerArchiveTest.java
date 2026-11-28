package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.masterdata.domain.OwnerAction;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerRuleCodes;
import com.huoling.wms.masterdata.domain.OwnerRuleViolation;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主档案聚合：两次状态迁移、停用态不可改档、编号不可变、版本冲突（tasks 1.8 的验证口径）。
 */
class OwnerArchiveTest {

    private static final Instant NOW = Instant.parse("2026-11-25T08:30:00Z");
    private static final String OWNER_NO = "OW000001";
    private static final String CREDIT_CODE = "91350100MA2Y1234XY";
    private static final long OPERATOR = 77L;

    @Test
    @DisplayName("建档：状态可用、版本 0、留痕为建档且原因为空")
    void 建档() {
        OwnerArchive archive = newArchive();

        assertThat(archive.status()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(archive.version()).isZero();
        assertThat(archive.ownerNo()).isEqualTo(OWNER_NO);
        assertThat(archive.lastStatusChange().action()).isEqualTo(OwnerAction.CREATE);
        assertThat(archive.lastStatusChange().reason()).isNull();
        assertThat(archive.lastStatusChange().operatorId()).isEqualTo(OPERATOR);
        assertThat(archive.createdBy()).isEqualTo(OPERATOR);
    }

    @Test
    @DisplayName("改档：版本递增一、状态与状态留痕不变、编号不变")
    void 改档() {
        OwnerArchive archive = newArchive();

        archive.edit(0L, fields("华东仓储（新）"), NOW.plusSeconds(60), 88L);

        assertThat(archive.version()).isEqualTo(1L);
        assertThat(archive.fields().ownerName()).isEqualTo("华东仓储（新）");
        assertThat(archive.status()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(archive.lastStatusChange().action()).isEqualTo(OwnerAction.CREATE);
        assertThat(archive.ownerNo()).isEqualTo(OWNER_NO);
        assertThat(archive.updatedBy()).isEqualTo(88L);
    }

    @Test
    @DisplayName("停用：状态转停用并记录原因、操作人与时间")
    void 停用() {
        OwnerArchive archive = newArchive();

        archive.disable(0L, "合同到期", NOW.plusSeconds(60), 88L);

        assertThat(archive.status()).isEqualTo(OwnerStatus.DISABLED);
        assertThat(archive.active()).isFalse();
        assertThat(archive.lastStatusChange().action()).isEqualTo(OwnerAction.DISABLE);
        assertThat(archive.lastStatusChange().reason()).isEqualTo("合同到期");
        assertThat(archive.lastStatusChange().operatorId()).isEqualTo(88L);
        assertThat(archive.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("停用态改档被拒：须先启用，规则码 STATE_INVALID")
    void 停用态不可改档() {
        OwnerArchive archive = newArchive();
        archive.disable(0L, "合同到期", NOW.plusSeconds(60), 88L);

        assertThatThrownBy(() -> archive.edit(1L, fields("改个名字"), NOW.plusSeconds(120), 88L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.STATE_INVALID);
        assertThat(archive.fields().ownerName()).isEqualTo("华东仓储有限公司");
        assertThat(archive.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("重复停用被拒：状态未变、版本不再递增")
    void 重复停用被拒() {
        OwnerArchive archive = newArchive();
        archive.disable(0L, "合同到期", NOW.plusSeconds(60), 88L);

        assertThatThrownBy(() -> archive.disable(1L, "再停一次", NOW.plusSeconds(120), 88L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.STATE_INVALID);
        assertThat(archive.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("启用：状态回可用、编号不变、留痕含原因与操作人")
    void 启用() {
        OwnerArchive archive = newOwnerInStatus(OwnerStatus.DISABLED);

        archive.enable(3L, "合同续签", NOW.plusSeconds(60), 99L);

        assertThat(archive.status()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(archive.ownerNo()).isEqualTo(OWNER_NO);
        assertThat(archive.lastStatusChange().action()).isEqualTo(OwnerAction.ENABLE);
        assertThat(archive.lastStatusChange().reason()).isEqualTo("合同续签");
        assertThat(archive.lastStatusChange().operatorId()).isEqualTo(99L);
        assertThat(archive.version()).isEqualTo(4L);
    }

    @Test
    @DisplayName("对可用货主再启用被拒")
    void 对可用再启用被拒() {
        OwnerArchive archive = newArchive();

        assertThatThrownBy(() -> archive.enable(0L, "重复启用", NOW.plusSeconds(60), 88L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.STATE_INVALID);
    }

    @Test
    @DisplayName("版本落后按可重试冲突拒绝，版本号不前进")
    void 版本冲突() {
        OwnerArchive archive = newArchive();
        archive.edit(0L, fields("第一次改名"), NOW.plusSeconds(60), 88L);

        assertThatThrownBy(() -> archive.edit(0L, fields("第二次改名"), NOW.plusSeconds(120), 88L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.CONFLICT_VERSION);
        assertThat(archive.version()).isEqualTo(1L);
        assertThat(archive.fields().ownerName()).isEqualTo("第一次改名");
    }

    @Test
    @DisplayName("未携带版本按参数错误拒绝")
    void 版本缺失() {
        OwnerArchive archive = newArchive();

        assertThatThrownBy(() -> archive.edit(null, fields("改个名字"), NOW, 88L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
        assertThatThrownBy(() -> archive.disable(null, "合同到期", NOW, 88L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("停用与启用必须给出原因")
    void 原因必填() {
        OwnerArchive archive = newArchive();
        assertThatThrownBy(() -> archive.disable(0L, "  ", NOW, 88L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);

        OwnerArchive disabled = newOwnerInStatus(OwnerStatus.DISABLED);
        assertThatThrownBy(() -> disabled.enable(3L, null, NOW, 88L))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("建档时编号形态非法或四项缺失一律拒绝")
    void 建档校验() {
        assertThatThrownBy(() -> OwnerArchive.create(1L, "OW001", fields("华东仓储有限公司"),
            OPERATOR, NOW))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);

        OwnerFields incomplete = new OwnerFields("华东仓储有限公司", null, "021-62345678",
            CREDIT_CODE);
        assertThatThrownBy(() -> OwnerArchive.create(1L, OWNER_NO, incomplete, OPERATOR, NOW))
            .isInstanceOf(OwnerRuleViolation.class)
            .extracting(e -> ((OwnerRuleViolation) e).code())
            .isEqualTo(OwnerRuleCodes.PARAM_INVALID);
    }

    private static OwnerArchive newArchive() {
        return OwnerArchive.create(1L, OWNER_NO, fields("华东仓储有限公司"), OPERATOR, NOW);
    }

    /** 处于指定状态的档案：留痕与版本按「已停用过一次」或「未变更」构造。 */
    private static OwnerArchive newOwnerInStatus(OwnerStatus status) {
        if (status == OwnerStatus.ACTIVE) {
            return newArchive();
        }
        return OwnerArchive.restore(1L, OWNER_NO, fields("华东仓储有限公司"), status,
            new OwnerStatusChange(OwnerAction.DISABLE, "合同到期", 88L, NOW.plusSeconds(60)),
            3L, NOW, OPERATOR, NOW.plusSeconds(60), 88L);
    }

    private static OwnerFields fields(String ownerName) {
        return new OwnerFields(ownerName, "张三", "021-62345678", CREDIT_CODE);
    }
}
