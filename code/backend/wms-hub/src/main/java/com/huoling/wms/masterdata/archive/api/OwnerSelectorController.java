package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.common.owner.OwnerPermissionCodes;
import com.huoling.wms.masterdata.archive.application.ArchiveAccessGuard;
import com.huoling.wms.masterdata.archive.application.OwnerSelectorService;
import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerRuleViolation;
import com.huoling.wms.masterdata.identity.api.CurrentUser;
import com.huoling.wms.masterdata.identity.api.RequiresPermission;
import com.huoling.wms.masterdata.identity.domain.DataScope;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 运营端货主选择器（API-020，**BREAKING** 改造，tasks 1.12），路径前缀 {@code /api/hub/owners}。
 *
 * <p>三处变化，都按契约 1.8 的 API-020 条目落地：
 * <ul>
 *   <li>数据源：从「货主端账号派生」换成权威主数据 {@code wms_owner}，只返回可用货主；</li>
 *   <li>参数：{@code ownerCode} / {@code name} 删除，改为 {@code ownerNo}（精确）/
 *       {@code ownerName}（模糊）；旧参数传入即 {@code PARAM_INVALID}，
 *       {@code details[].field} 给出旧名与新名的对应，不设过渡别名；</li>
 *   <li>权限点：由 {@code archive.hub.create} 改为 {@code owner.hub.read}——
 *       选择器的第二个用途是「开通货主端账号时指定货主」，而权限管理员只持主数据读，
 *       保留原权限点会让权限管理员开不出货主端账号。</li>
 * </ul>
 *
 * <p>旧参数为什么不在方法签名里留 {@code @RequestParam}：契约 1.11「选择器字段」要求
 * 「{@code ownerCode} 传参被 400 拒绝」，紧邻的验收行又要求旧参数<b>不再出现在生成物中</b>。
 * 只要旧名是绑定参数，产物里就必然出现一条 {@code deprecated} 声明（F-5 的实证），
 * 两条要求无法同时满足。改为从原始查询串识别：请求照旧被拒、字段名照旧回给调用方，
 * 产物里没有旧参数的痕迹。
 *
 * <p>数据域为会话货主域：域为「全部」返回全量，域为「指定列表」只返回域内货主，
 * 域为空直接 403（不回落成全量）。成功响应信封不带 {@code dataTimestamp}：读权威库、无副本。
 */
@RestController
@RequestMapping("/api/hub/owners")
public class OwnerSelectorController {

    private static final String API_QUERY = "API-020";

    /** 已删除的旧参数一：旧名，新名 {@code ownerNo}。 */
    private static final String LEGACY_OWNER_CODE = "ownerCode";
    /** 已删除的旧参数二：旧名，新名 {@code ownerName}。 */
    private static final String LEGACY_NAME = "name";

    private final OwnerSelectorService ownerSelectorService;
    private final ArchiveAccessGuard guard;
    private final JwtTokenService tokenService;

    public OwnerSelectorController(OwnerSelectorService ownerSelectorService,
                                   ArchiveAccessGuard guard,
                                   JwtTokenService tokenService) {
        this.ownerSelectorService = ownerSelectorService;
        this.guard = guard;
        this.tokenService = tokenService;
    }

    /**
     * API-020：按货主编号精确、货主名称模糊检索可用货主，键集分页。
     *
     * <p>旧参数检查放在最前面：调用方拿到的必须是「哪个参数改名了」，而不是一次空结果。
     */
    @GetMapping
    @RequiresPermission(OwnerPermissionCodes.READ)
    public OwnerSelectorResponse<OwnerSelectorView.OwnerSelectorPage> list(
            HttpServletRequest servletRequest,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Parameter(description = "货主编号，精确匹配", schema = @Schema(type = "string"))
            @RequestParam(required = false) String ownerNo,
            @Parameter(description = "货主名称，模糊匹配", schema = @Schema(type = "string"))
            @RequestParam(required = false) String ownerName,
            @Parameter(description = "排序字段：ownerNo、ownerName，缺省 ownerNo",
                schema = @Schema(type = "string")) @RequestParam(required = false) String sortBy,
            @Parameter(description = "排序方向：asc、desc，缺省 asc",
                schema = @Schema(type = "string")) @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        rejectRenamedParameter(servletRequest, LEGACY_OWNER_CODE,
            "参数 ownerCode 已废弃，请改用 ownerNo（货主编号精确匹配）");
        rejectRenamedParameter(servletRequest, LEGACY_NAME,
            "参数 name 已废弃，请改用 ownerName（货主名称模糊匹配）");
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        if (limit <= 0 || limit > OwnerSelectorService.MAX_LIMIT) {
            throw ArchiveParamException.of("limit",
                "参数 limit 取值 1 到 " + OwnerSelectorService.MAX_LIMIT);
        }
        guard.limitHub(API_QUERY);
        DataScope scope = guard.requireHubOwnerScope(user.accountId(), user.client());
        return OwnerSelectorResponse.ok(OwnerSelectorView.OwnerSelectorPage.of(
            ownerSelectorService.page(scope, ownerNo, ownerName, parseSort(sortBy, sortDir),
                cursor, limit)));
    }

    /**
     * 从原始查询串识别已删除的旧参数，命中即按 {@code PARAM_INVALID} 拒绝。
     *
     * <p>不用 {@code servletRequest.getParameterMap()}：那会先把参数名解码一遍，
     * 而这里要判的正是「调用方到底写了哪个名字」，直接看原始串更贴近实际请求；
     * 参数名按 UTF-8 解码，容忍被转义的写法与只有名字没有值的写法（{@code ?ownerCode}）。
     *
     * @param legacy  已删除的旧参数名，回给调用方作为 {@code details[].field}
     * @param message 提示语，正文给出新名与该参数的用法
     */
    private void rejectRenamedParameter(HttpServletRequest servletRequest, String legacy,
                                        String message) {
        String query = servletRequest.getQueryString();
        if (query == null || query.isEmpty()) {
            return;
        }
        for (String pair : query.split("&")) {
            if (legacy.equals(decodeQueryName(pair))) {
                throw ArchiveParamException.of(legacy, message);
            }
        }
    }

    /** 取一条查询参数的名字部分：{@code name=value} 取冒号前的部分，无等号即整段。 */
    private String decodeQueryName(String pair) {
        int separator = pair.indexOf('=');
        String raw = separator < 0 ? pair : pair.substring(0, separator);
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法百分号编码不是本方法的判定对象：按原文比对，交给后续参数解析去报错。
            return raw;
        }
    }

    /** 排序参数解析：白名单外取值按参数错误拒绝，并指出是哪个参数（契约 1.1「排序与筛选」）。 */
    private OwnerListSort parseSort(String sortBy, String sortDir) {
        try {
            return OwnerListSort.parse(sortBy, sortDir);
        } catch (OwnerRuleViolation e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            String field = message.contains("sortDir") ? "sortDir" : "sortBy";
            throw ArchiveParamException.of(field, message);
        }
    }
}
