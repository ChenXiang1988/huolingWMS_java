package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码映射逐行核对契约「错误码表」：HTTP 状态与 {@code retryable} 不允许与表格不一致。
 * 这张表同时是「错误码表每行都有落点」的构建期证据。
 */
class ArchiveHttpTest {

    @Test
    @DisplayName("参数、重复、状态、版本、越权、幂等、限流、依赖与内部错误的 HTTP 状态逐行对齐")
    void 状态映射() {
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.PARAM_INVALID)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.RULE_DUPLICATE_CODE))
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.RULE_DUPLICATE_BARCODE))
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.STATE_INVALID)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.CONFLICT_VERSION)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.NOT_FOUND_ARCHIVE)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.FORBIDDEN_OWNER)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.FORBIDDEN_SITE)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS))
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.IDEMPOTENT_KEY_CONFLICT))
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.RATE_LIMITED))
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.DEPENDENCY_UNAVAILABLE))
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ArchiveHttp.statusOf(ArchiveErrorCodes.INTERNAL_ERROR))
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("只有版本冲突、处理中、限流与依赖不可用可重试")
    void 可重试映射() {
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.CONFLICT_VERSION)).isTrue();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS)).isTrue();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.RATE_LIMITED)).isTrue();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.DEPENDENCY_UNAVAILABLE)).isTrue();

        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.PARAM_INVALID)).isFalse();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.RULE_DUPLICATE_CODE)).isFalse();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.RULE_DUPLICATE_BARCODE)).isFalse();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.STATE_INVALID)).isFalse();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.NOT_FOUND_ARCHIVE)).isFalse();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.FORBIDDEN_OWNER)).isFalse();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.FORBIDDEN_SITE)).isFalse();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.IDEMPOTENT_KEY_CONFLICT)).isFalse();
        assertThat(ArchiveHttp.retryable(ArchiveErrorCodes.INTERNAL_ERROR)).isFalse();
    }
}
