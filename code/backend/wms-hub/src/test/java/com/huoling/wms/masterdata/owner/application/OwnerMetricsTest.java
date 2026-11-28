package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.domain.OwnerRuleViolation;
import com.huoling.wms.masterdata.owner.support.BrokenMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 观测异常不改业务结果（design「可观测与兼容回滚」，复审提出的受理侧指标兜底）。
 *
 * <p>三处调用点的共同前提是「业务结果已经定了」：写动作已经成功或已经按业务规则失败、
 * 受理单已经落库且票据已经写进响应、越权拒绝已经判定。此时 Micrometer 抛错只应留下一条日志：
 * <ul>
 *   <li>成功翻成失败——调用方看到 500，重试时幂等键又已占位；</li>
 *   <li>业务失败码被指标异常顶替——{@code CONFLICT_VERSION} 变成 {@code INTERNAL_ERROR}；</li>
 *   <li>403 变 500——越权拒绝被记成系统故障，告警口径也跟着错。</li>
 * </ul>
 *
 * <p>用「注册即抛错」的 {@link BrokenMeterRegistry} 直接打这三条路径，
 * 与 {@code ArchiveOutboxDispatcherTest} 里「观测回调抛错不影响投递结果」同一套验证思路。
 */
class OwnerMetricsTest {

    private final OwnerMetrics metrics = new OwnerMetrics(new BrokenMeterRegistry());

    @Test
    @DisplayName("写动作成功：指标抛错仍返回业务结果，不把成功翻成失败")
    void 指标抛错不改成功结果() {
        assertThat(metrics.timed("API-023", () -> "owner-created"))
            .as("受理动作的返回值必须原样透出")
            .isEqualTo("owner-created");
    }

    @Test
    @DisplayName("写动作失败：指标抛错不顶替原错误码")
    void 指标抛错不顶替业务失败() {
        OwnerRuleViolation violation = new OwnerRuleViolation("CONFLICT_VERSION", "版本已过期");

        assertThatThrownBy(() -> metrics.timed("API-024", () -> {
            throw violation;
        }))
            .as("受理机制要按错误码把受理单落成 FAILED，不能被指标异常换掉")
            .isSameAs(violation);
    }

    @Test
    @DisplayName("受理计数：指标抛错不影响已提交的受理票据")
    void 指标抛错不影响受理票据() {
        assertThatCode(() -> {
            metrics.acceptSubmitted("API-023", false);
            metrics.acceptSubmitted("API-023", true);
        }).as("受理单已落库、响应已组装，计数失败只记日志").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("数据域拒绝计数：指标抛错不改变拒绝原因")
    void 指标抛错不改拒绝计数() {
        assertThatCode(() -> metrics.scopeDenied("client_not_hub", "accountId=1 client=OWNER"))
            .as("拒绝计数失败不能把随后的 FORBIDDEN_OWNER 顶掉")
            .doesNotThrowAnyException();
    }
}
