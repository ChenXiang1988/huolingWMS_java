package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.masterdata.acceptance.domain.AcceptanceException;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerRuleViolation;
import com.huoling.wms.masterdata.identity.api.GlobalExceptionHandler;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerParamException;
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
 * 货主档案接口的异常映射：把领域异常与入口解析异常翻成契约 1.4 的错误码与 HTTP 状态。
 *
 * <p>只作用于货主档案的两个控制器（{@code assignableTypes}）：商品档案与身份模块的响应包、
 * 映射规则都不受影响。为什么 owner 包自带一份而不是复用档案侧的处理器：
 * 架构断言要求两个包双向不得依赖（design AR-11），共用处理器会直接违规；
 * 两边的错误码表、HTTP 状态与 {@code retryable} 仍逐行照抄同一张契约表。
 *
 * <p>身份异常（未登录、无权限、账号停用）原样交回 {@link GlobalExceptionHandler}：
 * 它们由拦截器抛出，落在这里只是「经过」，不是业务失败，401/403 不能被兜底吞成 500。
 */
@RestControllerAdvice(assignableTypes = {HubOwnerArchiveController.class,
    HubOwnerAcceptanceController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OwnerExceptionHandler {

    private final GlobalExceptionHandler identityHandler;

    public OwnerExceptionHandler(GlobalExceptionHandler identityHandler) {
        this.identityHandler = identityHandler;
    }

    /** 货主档案业务异常；字段级错误把 {@code details} 一并回出（契约 1.1）。 */
    @ExceptionHandler(OwnerContractException.class)
    public ResponseEntity<OwnerResponse<Void>> handleContract(OwnerContractException e) {
        if (e instanceof OwnerParamException param && !param.issues().isEmpty()) {
            return failureWithDetails(e.code(), e.getMessage(), param.issues());
        }
        return failure(e.code(), e.getMessage(), e.retryAfterMs());
    }

    /** 受理与幂等设施的异常：码值与映射不变（幂等处理中、同键异体、受理通道饱和）。 */
    @ExceptionHandler(AcceptanceException.class)
    public ResponseEntity<OwnerResponse<Void>> handleAcceptance(AcceptanceException e) {
        return failure(e.code(), e.getMessage(), e.retryAfterMs());
    }

    /** Kernel 规则被违反：规则码本身就是契约错误码，消息里带字段名。 */
    @ExceptionHandler(OwnerRuleViolation.class)
    public ResponseEntity<OwnerResponse<Void>> handleRule(OwnerRuleViolation e) {
        return failure(e.code(), e.getMessage(), 0L);
    }

    /**
     * 请求体不是合法 JSON 或含未知字段（契约 1.2「输入校验」）。
     *
     * <p>未知字段由 {@code @JsonAnySetter} 抛出 {@link OwnerParamException}，Jackson 会把它包进
     * 反序列化异常；这里沿因果链找出它，把 {@code field} 原样回给调用方，
     * 不让「含未知字段」变成一句没有定位信息的 400。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OwnerResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        OwnerParamException fieldError = findFieldError(e);
        if (fieldError != null) {
            return failureWithDetails(fieldError.code(), fieldError.getMessage(),
                fieldError.issues());
        }
        return failure(OwnerErrorCodes.PARAM_INVALID, "请求体不合法或含未知字段", 0L);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<OwnerResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return failureWithDetails(OwnerErrorCodes.PARAM_INVALID,
            "参数 " + e.getName() + " 取值不合法：收到 " + e.getValue(),
            List.of(new OwnerFields.Issue(e.getName(), "取值不合法，收到 " + e.getValue())));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<OwnerResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return failureWithDetails(OwnerErrorCodes.PARAM_INVALID, "缺少参数 " + e.getParameterName(),
            List.of(new OwnerFields.Issue(e.getParameterName(), "必填参数缺失")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<OwnerResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        String message = errors.isEmpty()
            ? "请求参数校验失败"
            : errors.stream()
                .map(error -> "字段 " + error.getField() + " "
                    + (error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage()))
                .collect(Collectors.joining("；"));
        return failure(OwnerErrorCodes.PARAM_INVALID, message, 0L);
    }

    /** 存储或依赖不可用：503，不放行写操作（契约 1.2「降级」）。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<OwnerResponse<Void>> handleDataAccess(DataAccessException e) {
        return failure(OwnerErrorCodes.DEPENDENCY_UNAVAILABLE, "依赖服务不可用，请稍后重试", 1000L);
    }

    /** 兜底：未捕获异常统一 {@code INTERNAL_ERROR}，不向调用方暴露堆栈与 SQL。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception e) {
        if (e instanceof IdentityException identity) {
            return identityHandler.handleIdentity(identity);
        }
        return failure(OwnerErrorCodes.INTERNAL_ERROR, "服务内部错误，请带追踪编号报障", 0L);
    }

    private OwnerParamException findFieldError(Throwable throwable) {
        Throwable current = throwable;
        // 只沿因果链走，不做深度遍历：Jackson 的包装层数固定且很浅。
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof OwnerParamException param) {
                return param;
            }
            current = current.getCause();
        }
        return null;
    }

    private ResponseEntity<OwnerResponse<Void>> failureWithDetails(String code, String message,
                                                                   List<OwnerFields.Issue> details) {
        return ResponseEntity.status(OwnerHttp.statusOf(code))
            .body(OwnerResponse.invalid(message == null ? "请求未完成" : message, details));
    }

    private ResponseEntity<OwnerResponse<Void>> failure(String code, String message,
                                                        long retryAfterMs) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(OwnerHttp.statusOf(code));
        if (retryAfterMs > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, retryAfterMs / 1000L)));
        }
        String text = message == null ? "请求未完成" : message;
        OwnerResponse<Void> body = retryAfterMs > 0
            ? OwnerResponse.retryableFailure(code, text, retryAfterMs)
            : OwnerResponse.failure(code, text, OwnerHttp.retryable(code));
        return builder.body(body);
    }
}
