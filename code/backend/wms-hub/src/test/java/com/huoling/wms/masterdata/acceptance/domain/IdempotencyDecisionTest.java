package com.huoling.wms.masterdata.acceptance.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 幂等四个出口：首次执行、回放首次结果、同键异体冲突、处理中可重试。 */
class IdempotencyDecisionTest {

    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(3600);

    @Test
    @DisplayName("无记录即首次执行")
    void 无记录() {
        assertThat(IdempotencyDecision.decide(null, "fp-1", NOW))
            .isEqualTo(IdempotencyDecision.Outcome.PROCEED);
    }

    @Test
    @DisplayName("同键同指纹且已成功，回放首次结果")
    void 回放() {
        IdempotencyRecord record = new IdempotencyRecord("fp-1", IdempotencyStatus.SUCCEEDED,
            "{\"archiveNo\":\"SKU1\"}", LATER);
        assertThat(IdempotencyDecision.decide(record, "fp-1", NOW))
            .isEqualTo(IdempotencyDecision.Outcome.REPLAY);
    }

    @Test
    @DisplayName("同键异体返回冲突，首次结果不变")
    void 同键异体() {
        IdempotencyRecord record = new IdempotencyRecord("fp-1", IdempotencyStatus.SUCCEEDED,
            "{}", LATER);
        assertThat(IdempotencyDecision.decide(record, "fp-2", NOW))
            .isEqualTo(IdempotencyDecision.Outcome.CONFLICT);
    }

    @Test
    @DisplayName("同键同指纹仍在处理，返回可重试错误")
    void 处理中() {
        IdempotencyRecord record = new IdempotencyRecord("fp-1", IdempotencyStatus.IN_PROGRESS,
            null, LATER);
        assertThat(IdempotencyDecision.decide(record, "fp-1", NOW))
            .isEqualTo(IdempotencyDecision.Outcome.IN_PROGRESS);
    }

    @Test
    @DisplayName("窗口到期后同键可重新受理，不再回放旧结果")
    void 窗口到期() {
        IdempotencyRecord record = new IdempotencyRecord("fp-1", IdempotencyStatus.SUCCEEDED,
            "{}", NOW);
        assertThat(IdempotencyDecision.decide(record, "fp-1", NOW))
            .isEqualTo(IdempotencyDecision.Outcome.PROCEED);
    }

    @Test
    @DisplayName("上次失败不占用幂等键，同键可重试")
    void 失败可重试() {
        IdempotencyRecord record = new IdempotencyRecord("fp-1", IdempotencyStatus.FAILED,
            null, LATER);
        assertThat(IdempotencyDecision.decide(record, "fp-1", NOW))
            .isEqualTo(IdempotencyDecision.Outcome.PROCEED);
    }
}
