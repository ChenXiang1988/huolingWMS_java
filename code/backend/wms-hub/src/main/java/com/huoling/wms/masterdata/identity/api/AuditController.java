package com.huoling.wms.masterdata.identity.api;

import com.huoling.wms.masterdata.identity.application.AuditQueryService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 授权审计查询（API-017）。
 *
 * <p>过滤参数 {@code targetAccountId} 与条目里的 {@code id}、{@code operatorId}、
 * {@code targetAccountId} 四个标识按契约「标识、类型与枚举约定」收发十进制字符串：
 * 查询参数收到非十进制字符串按 {@code COMMON_9001} 拒绝，不出 5xx；
 * 非账号类操作没有目标账号，{@code targetAccountId} 为 {@code null}。
 */
@RestController
@RequestMapping("/hub/api/audit-logs")
public class AuditController {

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @RequiresPermission("hub.audit.read")
    public ApiResponse<PageResponse<AuditLogResponse>> list(
            @Parameter(description = "目标账号主键，十进制字符串",
                schema = @Schema(type = "string", pattern = Identifiers.PATTERN))
            @RequestParam(required = false) String targetAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long target = targetAccountId == null ? null : Identifiers.parse(targetAccountId, "targetAccountId");
        AuditQueryService.PageResult result = queryService.list(target, from, to, page, size);
        List<AuditLogResponse> items = result.items().stream().map(AuditController::toResponse).toList();
        return ApiResponse.ok(new PageResponse<>(items, result.total(), result.page(), result.size()),
            traceId());
    }

    private String traceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 契约 API-017 的条目：四个标识同为十进制字符串，{@code targetAccountId} 在非账号类操作时为 null。 */
    public record AuditLogResponse(
            @Schema(type = "string", pattern = Identifiers.PATTERN, description = "审计主键，十进制字符串")
            String id,
            @Schema(type = "string", pattern = Identifiers.PATTERN,
                description = "操作者账号主键，十进制字符串")
            String operatorId,
            @Schema(type = "string", pattern = Identifiers.PATTERN, nullable = true,
                description = "目标账号主键，十进制字符串；非账号类操作时为 null")
            String targetAccountId,
            String action,
            String beforeJson,
            String afterJson,
            OffsetDateTime createdAt) {
    }

    static AuditLogResponse toResponse(AuditQueryService.LogView view) {
        return new AuditLogResponse(Identifiers.toWire(view.id()), Identifiers.toWire(view.operatorId()),
            toWireTargetAccountId(view.targetAccountId()), view.action(), view.beforeJson(),
            view.afterJson(), view.createdAt());
    }

    /**
     * 目标账号的线上形态：{@code target_account_id} 列是 {@code NOT NULL}，角色、数据域这类
     * 非账号类操作在库内以 {@code 0} 占位，契约要求这类条目给 {@code null}。
     * 真实账号主键非零，因此 {@code 0} 只在边界还原成 {@code null}，不落成非法的 {@code "0"}。
     */
    private static String toWireTargetAccountId(Long value) {
        return value == null || value == 0L ? null : Identifiers.toWire(value);
    }
}
