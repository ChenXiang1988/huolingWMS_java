package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.acceptance.application.AcceptanceService;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 错误码到 HTTP 状态与 {@code retryable} 的映射，逐行照抄契约「错误码表」。
 * 只在接口层使用，领域层不带 HTTP 语义。
 */
final class ArchiveHttp {

    private ArchiveHttp() {
    }

    static HttpStatus statusOf(String code) {
        return switch (code) {
            case ArchiveErrorCodes.PARAM_INVALID -> HttpStatus.BAD_REQUEST;
            case ArchiveErrorCodes.RULE_DUPLICATE_CODE, ArchiveErrorCodes.RULE_DUPLICATE_BARCODE ->
                HttpStatus.UNPROCESSABLE_ENTITY;
            case ArchiveErrorCodes.STATE_INVALID, ArchiveErrorCodes.CONFLICT_VERSION,
                 ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS, ArchiveErrorCodes.IDEMPOTENT_KEY_CONFLICT ->
                HttpStatus.CONFLICT;
            case ArchiveErrorCodes.NOT_FOUND_ARCHIVE -> HttpStatus.NOT_FOUND;
            case ArchiveErrorCodes.FORBIDDEN_OWNER, ArchiveErrorCodes.FORBIDDEN_SITE -> HttpStatus.FORBIDDEN;
            case ArchiveErrorCodes.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case ArchiveErrorCodes.DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    static boolean retryable(String code) {
        return switch (code) {
            case ArchiveErrorCodes.CONFLICT_VERSION, ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS,
                 ArchiveErrorCodes.RATE_LIMITED, ArchiveErrorCodes.DEPENDENCY_UNAVAILABLE -> true;
            default -> false;
        };
    }

    /**
     * 写接口统一出口：一律 202，响应体只有受理号与受理时状态快照（契约「写接口统一响应」）。
     * 结果与最终版本号不在这里返回，由 API-015 至 API-017 查询。
     */
    static ResponseEntity<ArchiveResponse<AcceptanceService.Ticket>> accepted(
            AcceptanceService.Ticket ticket) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ArchiveResponse.ok(ticket));
    }
}
