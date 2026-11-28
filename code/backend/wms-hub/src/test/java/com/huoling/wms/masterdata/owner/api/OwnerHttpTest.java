package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码映射逐行核对契约 1.4 表（接口契约的构建期证据）。
 *
 * <p>三条一起看才叫「对账」：
 * <ul>
 *   <li>取值本身——{@code OwnerErrorCodes} 的字符串必须是契约里的符号码，不能是另一套字面量；</li>
 *   <li>HTTP 状态——类别决定状态码，改一个分支就让某一整类错误的语义漂移；</li>
 *   <li>{@code retryable}——由错误码决定而不是由 HTTP 推断（契约 1.2「统一错误体」），
 *       409 里既有可重试的版本冲突也有不可重试的状态冲突，混起来调用方就没法退避。</li>
 * </ul>
 */
class OwnerHttpTest {

    @Test
    @DisplayName("错误码取值与契约 1.4 表的符号码逐字一致")
    void 错误码取值对齐契约() {
        assertThat(OwnerErrorCodes.PARAM_INVALID).isEqualTo("PARAM_INVALID");
        assertThat(OwnerErrorCodes.RULE_DUPLICATE_CREDIT_CODE).isEqualTo("RULE_DUPLICATE_CREDIT_CODE");
        assertThat(OwnerErrorCodes.STATE_INVALID).isEqualTo("STATE_INVALID");
        assertThat(OwnerErrorCodes.CONFLICT_VERSION).isEqualTo("CONFLICT_VERSION");
        assertThat(OwnerErrorCodes.NOT_FOUND_OWNER).isEqualTo("NOT_FOUND_OWNER");
        assertThat(OwnerErrorCodes.FORBIDDEN_OWNER).isEqualTo("FORBIDDEN_OWNER");
        assertThat(OwnerErrorCodes.IDEMPOTENT_IN_PROGRESS).isEqualTo("IDEMPOTENT_IN_PROGRESS");
        assertThat(OwnerErrorCodes.IDEMPOTENT_KEY_CONFLICT).isEqualTo("IDEMPOTENT_KEY_CONFLICT");
        assertThat(OwnerErrorCodes.RATE_LIMITED).isEqualTo("RATE_LIMITED");
        assertThat(OwnerErrorCodes.DEPENDENCY_UNAVAILABLE).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(OwnerErrorCodes.INTERNAL_ERROR).isEqualTo("INTERNAL_ERROR");
    }

    @ParameterizedTest(name = "{0} → {1}（retryable={2}）")
    @CsvSource({
        "PARAM_INVALID,             400, false",
        "RULE_DUPLICATE_CREDIT_CODE, 422, false",
        "STATE_INVALID,              409, false",
        "CONFLICT_VERSION,           409, true",
        "NOT_FOUND_OWNER,            404, false",
        "FORBIDDEN_OWNER,            403, false",
        "IDEMPOTENT_IN_PROGRESS,     409, true",
        "IDEMPOTENT_KEY_CONFLICT,    409, false",
        "RATE_LIMITED,               429, true",
        "DEPENDENCY_UNAVAILABLE,     503, true",
        "INTERNAL_ERROR,             500, false",
    })
    @DisplayName("HTTP 状态与可重试标记逐行对齐契约 1.4")
    void 状态与可重试映射(String code, int status, boolean retryable) {
        assertThat(OwnerHttp.statusOf(code).value()).isEqualTo(status);
        assertThat(OwnerHttp.retryable(code)).isEqualTo(retryable);
    }

    @Test
    @DisplayName("契约外取值不猜状态：落到 500 且不可重试")
    void 契约外取值不猜() {
        assertThat(OwnerHttp.statusOf("SOMETHING_ELSE")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(OwnerHttp.retryable("SOMETHING_ELSE")).isFalse();
    }
}
