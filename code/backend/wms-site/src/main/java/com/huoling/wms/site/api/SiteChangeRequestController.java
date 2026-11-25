package com.huoling.wms.site.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.huoling.wms.common.archive.ArchivePermissionCodes;
import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.site.application.SiteAcceptanceService;
import com.huoling.wms.site.application.SiteChangeFields;
import com.huoling.wms.site.application.SiteChangeRequestService;
import com.huoling.wms.site.application.SiteRateLimiter;
import com.huoling.wms.site.domain.SiteException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单仓端变更申请（API-011 发起、API-014 查询），路径前缀 {@code /api/site/archive-change-requests}。
 *
 * <p>写接口按契约返回 202：响应只有受理号与受理时状态快照，申请编号在受理结果里给出，
 * 由 API-017 按受理号查询；本仓不改主数据，申请经上行写入 Hub 权威表（D12）。
 *
 * <p>入口同步校验（tasks 1.17、1.19）挡两类请求形态问题，都直接返回 400 {@code PARAM_INVALID}，
 * 不落受理记录、也不占用受理号：
 * <ul>
 *   <li>字段缺失、长度越界与请求体含未知字段；</li>
 *   <li>变更字段不在契约闭集内（闭集只有 {@code name}、{@code spec}、{@code category}、
 *       {@code storageCondition}，{@code baseUnit} 走运营改档）。</li>
 * </ul>
 *
 * <p>档案是否在本仓可见、是否已有待审核申请仍由应用层判定：这两条依赖本仓缓存与库内状态，
 * 结论落在受理结果里，由 API-017 按受理号查询。
 */
@RestController
@RequestMapping("/api/site/archive-change-requests")
public class SiteChangeRequestController {

    private static final String API_CREATE = "API-011";
    private static final String API_QUERY = "API-014";
    /** 变更申请受理时的业务状态快照：新建申请固定为待审核（21 E-16）。 */
    private static final String SNAPSHOT_PENDING = "PENDING";
    /** 档案编号列宽：与 Hub 的 {@code archive_no} 同为 {@code varchar(32)}。 */
    private static final int ARCHIVE_ID_MAX = 32;
    /** 变更字段列宽：{@code site_archive_change_request.change_field} 为 {@code varchar(32)}。 */
    private static final int CHANGE_FIELD_MAX = 32;
    /** 目标值与原因列宽：{@code site_archive_change_request} 两列均为 {@code varchar(512)}。 */
    private static final int TEXT_MAX = 512;

    private final SiteChangeRequestService changeRequestService;
    private final SiteAcceptanceService acceptanceService;
    private final SiteRateLimiter rateLimiter;

    public SiteChangeRequestController(SiteChangeRequestService changeRequestService,
                                       SiteAcceptanceService acceptanceService,
                                       SiteRateLimiter rateLimiter) {
        this.changeRequestService = changeRequestService;
        this.acceptanceService = acceptanceService;
        this.rateLimiter = rateLimiter;
    }

    /** API-011：发起字段变更申请，幂等作用域为本仓加接口加键。 */
    @PostMapping
    @ApiResponses({
        @ApiResponse(responseCode = "202",
            description = "已受理：data 只含 acceptNo 与受理时状态快照 status，执行结果由 API-017 按受理号查询",
            content = @Content(schema = @Schema(implementation = SiteApiResponse.class))),
        @ApiResponse(responseCode = "400",
            description = "PARAM_INVALID：字段缺失、长度越界、请求体含未知字段，或 changeField 不在闭集"
                + "（name、spec、category、storageCondition）内；缺 Idempotency-Key 也按此拒绝",
            content = @Content(schema = @Schema(implementation = SiteApiResponse.class))),
        @ApiResponse(responseCode = "403",
            description = "FORBIDDEN_SITE：单仓越权发起变更申请，或令牌端标识与仓标识不合法",
            content = @Content(schema = @Schema(implementation = SiteApiResponse.class))),
        @ApiResponse(responseCode = "404",
            description = "NOT_FOUND_ARCHIVE：档案不存在或不在本仓可见范围内",
            content = @Content(schema = @Schema(implementation = SiteApiResponse.class))),
        @ApiResponse(responseCode = "409",
            description = "STATE_INVALID、IDEMPOTENT_IN_PROGRESS、IDEMPOTENT_KEY_CONFLICT："
                + "档案状态不接受变更申请、幂等记录处理中或同键异体",
            content = @Content(schema = @Schema(implementation = SiteApiResponse.class))),
        @ApiResponse(responseCode = "429",
            description = "RATE_LIMITED：本仓加接口超限，响应体带 retryAfterMs",
            content = @Content(schema = @Schema(implementation = SiteApiResponse.class))),
        @ApiResponse(responseCode = "503",
            description = "DEPENDENCY_UNAVAILABLE：本仓数据依赖或上行通道不可用，可重试",
            content = @Content(schema = @Schema(implementation = SiteApiResponse.class))),
        @ApiResponse(responseCode = "500",
            description = "INTERNAL_ERROR：未捕获系统异常，带 traceId 报障",
            content = @Content(schema = @Schema(implementation = SiteApiResponse.class)))
    })
    public ResponseEntity<SiteApiResponse<SiteAcceptanceService.Ticket>> create(
            HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateRequest body) {
        SiteSession session = SiteSessions.require(request, ArchivePermissionCodes.SITE_CHANGE);
        rateLimiter.check(session.siteId(), API_CREATE);
        requireText(body.archiveId(), "archiveId", ARCHIVE_ID_MAX);
        requireText(body.changeField(), "changeField", CHANGE_FIELD_MAX);
        SiteChangeFields.require(body.changeField());
        requireText(body.targetValue(), "targetValue", TEXT_MAX);
        requireText(body.reason(), "reason", TEXT_MAX);
        SiteAcceptanceService.Ticket ticket = acceptanceService.submit(session.siteId(), API_CREATE,
            idempotencyKey, body.archiveId(), body.toString(), SNAPSHOT_PENDING,
            acceptNo -> changeRequestService.create(session.siteId(), body.archiveId(),
                body.changeField(), body.targetValue(), body.reason(), acceptNo));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(SiteApiResponse.ok(ticket));
    }

    /** API-014：本仓申请列表，含驳回原因，可按档案过滤与按申请字段排序。 */
    @GetMapping
    public SiteApiResponse<SiteChangeRequestService.ChangeRequestPage> list(
            HttpServletRequest request,
            @RequestParam(required = false) String requestNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String archiveId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        SiteSession session = SiteSessions.require(request, ArchivePermissionCodes.SITE_READ);
        rateLimiter.check(session.siteId(), API_QUERY);
        SkuListSort sort = SkuListSort.parse(sortBy, sortDir);
        return SiteApiResponse.ok(changeRequestService.page(session.siteId(), status, requestNo,
            archiveId, sort, cursor, limit));
    }

    /** 入口必填与长度校验：去空白后非空且不超过库列宽，失败按 {@code PARAM_INVALID} 拒绝。 */
    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID, "字段 " + field + " 必填");
        }
        if (value.length() > maxLength) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "字段 " + field + " 长度上限 " + maxLength);
        }
    }

    /** API-011 请求体：档案编号、变更字段（契约闭集）、目标值与原因。 */
    public record CreateRequest(String archiveId, String changeField, String targetValue,
                                String reason) {

        /**
         * 未知字段一律拒绝：变更申请只接受契约字段闭集内的四个字段。
         *
         * <p>与 Hub 侧同一写法：忽略未知字段会让调用方以为写成功、实际没写。
         * 抛出的 {@code SiteException} 由 {@code SiteExceptionHandler} 翻成 400 {@code PARAM_INVALID}。
         */
        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "请求体含未知字段 " + fieldName + "：变更申请只接受契约声明的字段");
        }
    }
}
