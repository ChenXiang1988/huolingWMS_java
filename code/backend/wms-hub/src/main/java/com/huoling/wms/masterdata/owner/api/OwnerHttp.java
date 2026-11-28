package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.masterdata.acceptance.application.AcceptanceService;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 错误码到 HTTP 状态与 {@code retryable} 的映射，逐行照抄契约 1.4 表。
 * 只在接口层使用，领域层不带 HTTP 语义。
 */
final class OwnerHttp {

    private OwnerHttp() {
    }

    static HttpStatus statusOf(String code) {
        return switch (code) {
            case OwnerErrorCodes.PARAM_INVALID -> HttpStatus.BAD_REQUEST;
            case OwnerErrorCodes.RULE_DUPLICATE_CREDIT_CODE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case OwnerErrorCodes.STATE_INVALID, OwnerErrorCodes.CONFLICT_VERSION,
                 OwnerErrorCodes.IDEMPOTENT_IN_PROGRESS, OwnerErrorCodes.IDEMPOTENT_KEY_CONFLICT ->
                HttpStatus.CONFLICT;
            case OwnerErrorCodes.NOT_FOUND_OWNER -> HttpStatus.NOT_FOUND;
            case OwnerErrorCodes.FORBIDDEN_OWNER -> HttpStatus.FORBIDDEN;
            case OwnerErrorCodes.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case OwnerErrorCodes.DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    static boolean retryable(String code) {
        return switch (code) {
            case OwnerErrorCodes.CONFLICT_VERSION, OwnerErrorCodes.IDEMPOTENT_IN_PROGRESS,
                 OwnerErrorCodes.RATE_LIMITED, OwnerErrorCodes.DEPENDENCY_UNAVAILABLE -> true;
            default -> false;
        };
    }

    /**
     * 写接口统一出口：一律 202，响应体只有受理票据（契约 1.6）。
     *
     * <p>结果摘要（含最终状态与版本号）不在这里返回，由 API-027 查询。
     */
    static ResponseEntity<OwnerResponse<OwnerTicket>> accepted(
            AcceptanceService.Submission submission) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(OwnerResponse.ok(OwnerTicket.of(submission)));
    }

    /**
     * 受理票据：受理号、受理时业务状态快照与是否重复请求（契约 1.6）。
     *
     * <p>{@code status} 是受理时的快照，不是执行结果：调用方 MUST NOT 用它判断成功与否，
     * 结论只从 API-027 取。
     */
    record OwnerTicket(String acceptNo, String status, boolean replayed) {

        static OwnerTicket of(AcceptanceService.Submission submission) {
            return new OwnerTicket(submission.acceptNo(), submission.status(), submission.replayed());
        }
    }
}
