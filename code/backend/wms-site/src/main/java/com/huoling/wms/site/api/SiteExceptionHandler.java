package com.huoling.wms.site.api;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import com.huoling.wms.site.domain.SiteException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 单仓端异常映射：把领域异常与入口解析异常翻成契约错误码与 HTTP 状态。
 *
 * <p>与 Hub 侧同一张映射表：限流与处理中带 {@code retryAfterMs}（响应体与 {@code Retry-After} 头各一份），
 * 未捕获异常一律 {@code INTERNAL_ERROR}（契约错误码表最后一行），不向调用方暴露堆栈。
 *
 * <p><b>作用范围只限本端业务控制器</b>（{@code assignableTypes}，与 Hub 侧档案处理器同一写法）。
 * 此前这里是不带选择器的全局 advice：{@code @ExceptionHandler(Exception.class)} 的选择器为空时对任何
 * 处理器都生效（{@code HandlerTypePredicate} 无选择器即放行），于是文档路径这类没有匹配控制器、
 * 由 {@code ResourceHttpRequestHandler} 抛出的 {@code NoResourceFoundException} 也被兜底吃成
 * 500 {@code INTERNAL_ERROR}，把「找不到文档端点」掩盖成「服务内部错误」，文档生成侧的真实原因随之丢失。
 * 限定到下列控制器后，文档路径（{@code /v3/api-docs}、{@code /swagger-ui/**}）既不是这些控制器的
 * 处理方法、也不会经过本处理器，异常交回框架按原语义（404/由 SpringDoc 自己生成）处理；
 * 档案接口的 500 兜底照旧保留，不因为收窄范围而丢掉契约错误码表最后一行。
 */
@RestControllerAdvice(assignableTypes = {
    SiteArchiveController.class,
    SiteChangeRequestController.class,
    SiteAcceptanceController.class})
public class SiteExceptionHandler {

    @ExceptionHandler(SiteException.class)
    public ResponseEntity<SiteApiResponse<Void>> handleSite(SiteException e) {
        return failure(e.code(), e.getMessage(), e.retryAfterMs());
    }

    /** Kernel 规则被违反（状态、判重、版本、参数），规则码直接就是契约错误码。 */
    @ExceptionHandler(SkuRuleViolation.class)
    public ResponseEntity<SiteApiResponse<Void>> handleRule(SkuRuleViolation e) {
        return failure(e.code(), e.getMessage(), 0L);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<SiteApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        String detail = e.getMostSpecificCause() == null ? "" : "：" + e.getMostSpecificCause().getMessage();
        return failure(ArchiveContractCodes.PARAM_INVALID, "请求体不合法或含未知字段" + detail, 0L);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<SiteApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return failure(ArchiveContractCodes.PARAM_INVALID,
            "参数 " + e.getName() + " 取值不合法：收到 " + e.getValue(), 0L);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<SiteApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return failure(ArchiveContractCodes.PARAM_INVALID, "缺少参数 " + e.getParameterName(), 0L);
    }

    /** 本仓库或依赖不可用：503，可重试（契约「降级约定」：缓存不可用时不放行写操作）。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<SiteApiResponse<Void>> handleDataAccess(DataAccessException e) {
        return failure(ArchiveContractCodes.DEPENDENCY_UNAVAILABLE, "依赖服务不可用，请稍后重试", 1000L);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SiteApiResponse<Void>> handleUnexpected(Exception e) {
        return failure(ArchiveContractCodes.INTERNAL_ERROR, "服务内部错误，请带追踪编号报障", 0L);
    }

    private ResponseEntity<SiteApiResponse<Void>> failure(String code, String message,
                                                          long retryAfterMs) {
        HttpStatus status = statusOf(code);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (retryAfterMs > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, retryAfterMs / 1000L)));
        }
        String text = message == null ? "请求未完成" : message;
        SiteApiResponse<Void> body = retryAfterMs > 0
            ? SiteApiResponse.retryableFailure(code, text, retryAfterMs)
            : SiteApiResponse.failure(code, text, retryable(code));
        return builder.body(body);
    }

    static HttpStatus statusOf(String code) {
        return switch (code) {
            case SiteTokenVerifier.TOKEN_INVALID_CODE -> HttpStatus.UNAUTHORIZED;
            case ArchiveContractCodes.PARAM_INVALID -> HttpStatus.BAD_REQUEST;
            case ArchiveContractCodes.STATE_INVALID, ArchiveContractCodes.CONFLICT_VERSION,
                 ArchiveContractCodes.IDEMPOTENT_IN_PROGRESS,
                 ArchiveContractCodes.IDEMPOTENT_KEY_CONFLICT -> HttpStatus.CONFLICT;
            case ArchiveContractCodes.NOT_FOUND_ARCHIVE -> HttpStatus.NOT_FOUND;
            case ArchiveContractCodes.FORBIDDEN_OWNER,
                 ArchiveContractCodes.FORBIDDEN_SITE -> HttpStatus.FORBIDDEN;
            case ArchiveContractCodes.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case ArchiveContractCodes.DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    static boolean retryable(String code) {
        return switch (code) {
            case ArchiveContractCodes.CONFLICT_VERSION, ArchiveContractCodes.IDEMPOTENT_IN_PROGRESS,
                 ArchiveContractCodes.RATE_LIMITED,
                 ArchiveContractCodes.DEPENDENCY_UNAVAILABLE -> true;
            default -> false;
        };
    }
}
