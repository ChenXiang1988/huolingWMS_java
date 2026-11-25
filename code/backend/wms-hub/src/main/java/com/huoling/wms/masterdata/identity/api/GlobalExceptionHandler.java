package com.huoling.wms.masterdata.identity.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.huoling.wms.masterdata.identity.application.RateLimitExceededException;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把领域异常与入口解析异常映射为契约错误码与 HTTP 状态。
 * 所有失败响应一律走统一响应包 {@code {code, message, data, traceId, retryable}}，
 * 不使用 Spring 默认错误体（契约「鲁棒性约定·入口拒绝」）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdentity(IdentityException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(statusOf(e.code()));
        if (e instanceof RateLimitExceededException rateLimited) {
            // 契约 API-001：429 必须带 Retry-After，调用方按它等待。
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimited.retryAfterSeconds()));
        }
        return builder.body(ApiResponse.fail(ErrorEnvelope.wireCode(e.code()), e.getMessage(),
            ErrorEnvelope.traceId(), ErrorEnvelope.retryable(e.code())));
    }

    /** Bean Validation 失败：指出哪个字段不合法。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        String message = errors.isEmpty()
            ? "请求参数校验失败"
            : errors.stream()
                .map(error -> "字段 " + error.getField() + " " + defaultMessage(error))
                .collect(Collectors.joining("；"));
        return badRequest(message);
    }

    /** 请求体不是合法 JSON，或契约内字段类型不符（如 {@code employeeId} 传字符串）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return badRequest(describe(e));
    }

    /** 查询参数类型不符（如 {@code status}、{@code client} 传闭集外取值或非整数）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        StringBuilder message = new StringBuilder("参数 ").append(e.getName())
            .append(" 取值不合法：收到 ").append(quote(e.getValue()));
        List<String> allowed = enumValues(e.getRequiredType());
        if (!allowed.isEmpty()) {
            message.append("，只接受 ").append(String.join("、", allowed));
        } else if (isIntegerLike(e.getRequiredType())) {
            message.append("，只接受整数");
        }
        return badRequest(message.toString());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return badRequest("缺少参数 " + e.getParameterName());
    }

    /** 权限或存储服务不可用（含 Redis、数据库连接失败）：503，不放行（契约 COMMON_9003）。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.fail(ErrorEnvelope.wireCode(IdentityErrorCodes.COMMON_UNAVAILABLE),
                "权限或存储服务不可用，请稍后重试", ErrorEnvelope.traceId(), true));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.badRequest()
            .body(ApiResponse.fail(ErrorEnvelope.wireCode(IdentityErrorCodes.COMMON_BAD_REQUEST),
                message, ErrorEnvelope.traceId(), false));
    }

    /**
     * 从 Jackson 异常里取出出错字段名与期望类型，拼成「字段 X 类型不合法」这类可执行提示。
     */
    private String describe(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof InvalidFormatException invalid) {
            String field = lastFieldName(invalid);
            List<String> allowed = enumValues(invalid.getTargetType());
            if (!allowed.isEmpty()) {
                return "字段 " + field + " 取值不合法：收到 " + quote(invalid.getValue())
                    + "，只接受 " + String.join("、", allowed);
            }
            return "字段 " + field + " 类型不合法：收到 " + quote(invalid.getValue())
                + describeTarget(invalid.getTargetType());
        }
        if (cause instanceof MismatchedInputException mismatched) {
            String field = lastFieldName(mismatched);
            if (!field.isEmpty()) {
                return "字段 " + field + " 类型不合法" + describeTarget(mismatched.getTargetType());
            }
        }
        if (cause instanceof JsonMappingException mapping) {
            String field = lastFieldName(mapping);
            if (!field.isEmpty()) {
                return "字段 " + field + " 类型不合法";
            }
        }
        return "请求体不是合法 JSON";
    }

    private String describeTarget(Class<?> targetType) {
        if (targetType == String.class) {
            // 契约「标识、类型与枚举约定」：标识只收十进制字符串，数字形态按 COMMON_9001 拒绝。
            return "，标识必须为字符串形态（" + Identifiers.PATTERN + "），不要把标识写成 JSON 数字";
        }
        if (isIntegerLike(targetType)) {
            return "，只接受整数";
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return "，只接受布尔值";
        }
        return "";
    }

    private String lastFieldName(JsonMappingException e) {
        return e.getPath().stream()
            .map(JsonMappingException.Reference::getFieldName)
            .filter(name -> name != null && !name.isBlank())
            .reduce((first, second) -> second)
            .orElse("");
    }

    private List<String> enumValues(Class<?> type) {
        if (type == null || !type.isEnum()) {
            return List.of();
        }
        return Arrays.stream(type.getEnumConstants()).map(String::valueOf).toList();
    }

    private boolean isIntegerLike(Class<?> type) {
        return type == Long.class || type == long.class || type == Integer.class
            || type == int.class || type == java.math.BigInteger.class;
    }

    private String quote(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private String defaultMessage(FieldError error) {
        return error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage();
    }

    private HttpStatus statusOf(String code) {
        return switch (code) {
            case IdentityErrorCodes.AUTH_BAD_CREDENTIALS,
                 IdentityErrorCodes.AUTH_TOKEN_INVALID,
                 IdentityErrorCodes.AUTH_ACCOUNT_DISABLED,
                 IdentityErrorCodes.AUTH_REFRESH_INVALID -> HttpStatus.UNAUTHORIZED;
            case IdentityErrorCodes.AUTHZ_NO_PERMISSION,
                 IdentityErrorCodes.AUTHZ_EMPTY_SCOPE,
                 IdentityErrorCodes.AUTHZ_SCOPE_EXCEEDED,
                 IdentityErrorCodes.AUTHZ_SELF_OPERATION -> HttpStatus.FORBIDDEN;
            case IdentityErrorCodes.AUTHZ_LAST_ADMIN,
                 IdentityErrorCodes.ACCOUNT_NAME_TAKEN,
                 IdentityErrorCodes.ACCOUNT_EMPLOYEE_TAKEN,
                 IdentityErrorCodes.COMMON_VERSION_CONFLICT,
                 IdentityErrorCodes.COMMON_IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case IdentityErrorCodes.ACCOUNT_NOT_FOUND,
                 IdentityErrorCodes.ROLE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IdentityErrorCodes.COMMON_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case IdentityErrorCodes.COMMON_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

}
