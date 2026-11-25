package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import com.huoling.wms.masterdata.identity.api.GlobalExceptionHandler;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品档案接口的异常映射：把领域异常与入口解析异常翻成契约错误码与 HTTP 状态。
 *
 * <p>只作用于档案控制器（{@code assignableTypes}），身份模块的响应包与映射规则不受影响。
 * 兜底处理器存在的前提是：拦截器与令牌校验抛出的 {@link IdentityException} 在这里被识别并交回
 * 身份的 {@link GlobalExceptionHandler}，401/403 语义不会被兜底吞成 500。
 * 其余未捕获异常一律返回 {@code INTERNAL_ERROR}（契约错误码表最后一行），不向调用方暴露堆栈。
 */
@RestControllerAdvice(assignableTypes = {
    OwnerArchiveController.class,
    HubArchiveController.class,
    HubChangeRequestController.class,
    SiteChangeRequestController.class,
    OwnerAcceptanceController.class,
    HubAcceptanceController.class,
    SiteAuthController.class,
    OwnerSelectorController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ArchiveExceptionHandler {

    private final GlobalExceptionHandler identityHandler;

    public ArchiveExceptionHandler(GlobalExceptionHandler identityHandler) {
        this.identityHandler = identityHandler;
    }

    @ExceptionHandler(ArchiveException.class)
    public ResponseEntity<ArchiveResponse<Void>> handleArchive(ArchiveException e) {
        return failure(e.code(), e.getMessage(), e.retryAfterMs());
    }

    /** Kernel 规则被违反（状态、判重、版本、参数），规则码直接就是契约错误码。 */
    @ExceptionHandler(SkuRuleViolation.class)
    public ResponseEntity<ArchiveResponse<Void>> handleRule(SkuRuleViolation e) {
        return failure(e.code(), e.getMessage(), 0L);
    }

    /** 请求体不是合法 JSON 或含未知字段（契约 API-001「未知字段拒绝」）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ArchiveResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        String detail = e.getMostSpecificCause() == null ? "" : "：" + e.getMostSpecificCause().getMessage();
        return failure(ArchiveErrorCodes.PARAM_INVALID, "请求体不合法或含未知字段" + detail, 0L);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ArchiveResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return failure(ArchiveErrorCodes.PARAM_INVALID,
            "参数 " + e.getName() + " 取值不合法：收到 " + e.getValue(), 0L);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ArchiveResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return failure(ArchiveErrorCodes.PARAM_INVALID, "缺少参数 " + e.getParameterName(), 0L);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ArchiveResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        String message = errors.isEmpty()
            ? "请求参数校验失败"
            : errors.stream()
                .map(error -> "字段 " + error.getField() + " "
                    + (error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage()))
                .collect(Collectors.joining("；"));
        return failure(ArchiveErrorCodes.PARAM_INVALID, message, 0L);
    }

    /** 存储或依赖不可用：503，不放行写操作（契约「降级约定」）。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ArchiveResponse<Void>> handleDataAccess(DataAccessException e) {
        return failure(ArchiveErrorCodes.DEPENDENCY_UNAVAILABLE, "依赖服务不可用，请稍后重试", 1000L);
    }

    /**
     * 兜底：未捕获异常统一 {@code INTERNAL_ERROR}，错误体四字段必填项齐全。
     *
     * <p>身份异常（未登录、无权限、账号停用）原样交回身份模块的处理器，
     * 因为它们由拦截器抛出，落在这里只是「经过」，不是业务失败。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception e) {
        if (e instanceof IdentityException identity) {
            return identityHandler.handleIdentity(identity);
        }
        return failure(ArchiveErrorCodes.INTERNAL_ERROR, "服务内部错误，请带追踪编号报障", 0L);
    }

    private ResponseEntity<ArchiveResponse<Void>> failure(String code, String message,
                                                          long retryAfterMs) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ArchiveHttp.statusOf(code));
        if (retryAfterMs > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, retryAfterMs / 1000L)));
        }
        String text = message == null ? "请求未完成" : message;
        ArchiveResponse<Void> body = retryAfterMs > 0
            ? ArchiveResponse.retryableFailure(code, text, retryAfterMs)
            : ArchiveResponse.failure(code, text, ArchiveHttp.retryable(code));
        return builder.body(body);
    }
}
