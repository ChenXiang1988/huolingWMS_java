package com.huoling.wms.masterdata.owner.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * 货主档案八个操作（API-020 至 API-027）的响应声明：写接口 202 加逐条错误码（契约 1.4、1.6、1.8）。
 *
 * <p><b>为什么用 {@link OpenApiCustomizer} 而不是方法上的 {@code @ApiResponse}：</b>
 * 实测（springdoc 2.6.0）只要方法上出现一个 {@code @ApiResponse}，SpringDoc 就用注解里的响应
 * <b>整份替换</b>由返回类型生成的那条成功响应，而不是与它合并。后果是成功响应退化成一个
 * 泛型擦除后的裸信封（{@code data} 变成 {@code object}），
 * {@code OwnerResponse<OwnerTicket>} 这类具体化 schema（{@code data} 指向 {@code OwnerTicket}）
 * 会从 {@code components} 里整体消失——契约要的「受理票据」「详情字段」正好落在那里。
 *
 * <p>本类只改 {@code responses}，不动返回类型派生的内容：成功那条原样保留（写接口把状态码
 * 从 200 挪到 202，描述换成契约 1.6 的受理口径），再把契约 1.4 里该接口出现的错误码追加在后面。
 * 错误体与成功体是同一信封（契约 1.1「一个结构，不按端分叉」），所以错误响应不需要额外 schema。
 *
 * <p>错误码按契约 1.4「出现位置」列分配，不按「可能发生」猜：{@code RULE_DUPLICATE_CREDIT_CODE}、
 * {@code STATE_INVALID}、{@code CONFLICT_VERSION} 只出现在 API-027 的受理结果里，
 * 因此四个写接口的同步响应不声明 422 与这两类 409。
 *
 * <p>路径不在产物里时直接失败，不静默跳过：那意味着控制器被改名或删了，属于契约破坏，
 * 应当在导出时就让构建变红（{@code OwnerContractDocumentTest} 也逐条断言这 8 组路径与方法）。
 */
@Component
public class OwnerContractOpenApi implements OpenApiCustomizer {

    private static final String OWNER_SELECTOR = "/api/hub/owners";
    private static final String OWNER_ARCHIVES = "/api/hub/owner-archives";
    private static final String OWNER_ARCHIVE = "/api/hub/owner-archives/{ownerNo}";
    private static final String OWNER_DISABLE = "/api/hub/owner-archives/{ownerNo}/disable";
    private static final String OWNER_ENABLE = "/api/hub/owner-archives/{ownerNo}/enable";
    private static final String OWNER_ACCEPTANCE = "/api/hub/owner-archives/acceptances/{acceptNo}";

    private static final String OK = "200";
    private static final String ACCEPTED = "202";
    private static final String PARAM_INVALID = "400";
    private static final String FORBIDDEN = "403";
    private static final String NOT_FOUND = "404";
    private static final String CONFLICT = "409";
    private static final String RATE_LIMITED = "429";
    private static final String INTERNAL = "500";
    private static final String UNAVAILABLE = "503";

    private static final String DESCRIPTION_ACCEPTED =
        "已受理：data 只含 acceptNo、受理时状态快照 status 与 replayed，执行结果由 API-027 按受理号查询";
    private static final String DESCRIPTION_PARAM_INVALID =
        "PARAM_INVALID：必填缺失、长度或枚举越界、请求体未知字段、limit 与 cursor 越界、"
            + "Idempotency-Key 缺失或格式非法、传入已删除的 ownerCode 或 name";
    private static final String DESCRIPTION_FORBIDDEN =
        "FORBIDDEN_OWNER：令牌端标识不是 HUB，或货主数据域为指定列表且列表为空，"
            + "或指定列表域尝试建档";
    private static final String DESCRIPTION_NOT_FOUND =
        "NOT_FOUND_OWNER：货主编号或受理号不存在，或存在但不在调用方货主域内（两者同一响应）";
    private static final String DESCRIPTION_CONFLICT =
        "IDEMPOTENT_IN_PROGRESS、IDEMPOTENT_KEY_CONFLICT：同一幂等键的首次请求仍在处理（可重试），"
            + "或同键但请求指纹不同（不可重试）";
    private static final String DESCRIPTION_RATE_LIMITED =
        "RATE_LIMITED：运营端该接口超过配额，响应带 Retry-After 与 retryAfterMs";
    private static final String DESCRIPTION_INTERNAL =
        "INTERNAL_ERROR：未捕获异常，响应带 traceId 供报障";
    private static final String DESCRIPTION_UNAVAILABLE =
        "DEPENDENCY_UNAVAILABLE：权限、数据域或受理存储不可用，可重试";

    @Override
    public void customise(OpenAPI openApi) {
        // API-020 选择器：读接口，无幂等键、无目标对象，因此没有 404 与 409。
        declare(openApi, OWNER_SELECTOR, PathItem.HttpMethod.GET, false,
            PARAM_INVALID, FORBIDDEN, RATE_LIMITED, INTERNAL, UNAVAILABLE);
        // API-021 列表、API-022 详情。
        declare(openApi, OWNER_ARCHIVES, PathItem.HttpMethod.GET, false,
            PARAM_INVALID, FORBIDDEN, RATE_LIMITED, INTERNAL, UNAVAILABLE);
        declare(openApi, OWNER_ARCHIVE, PathItem.HttpMethod.GET, false,
            PARAM_INVALID, FORBIDDEN, NOT_FOUND, RATE_LIMITED, INTERNAL, UNAVAILABLE);
        // API-023 至 API-026 四个写接口：202 加同步错误码（受理结果的错误码走 API-027）。
        declare(openApi, OWNER_ARCHIVES, PathItem.HttpMethod.POST, true,
            PARAM_INVALID, FORBIDDEN, CONFLICT, RATE_LIMITED, INTERNAL, UNAVAILABLE);
        declare(openApi, OWNER_ARCHIVE, PathItem.HttpMethod.PUT, true,
            PARAM_INVALID, FORBIDDEN, CONFLICT, RATE_LIMITED, INTERNAL, UNAVAILABLE);
        declare(openApi, OWNER_DISABLE, PathItem.HttpMethod.POST, true,
            PARAM_INVALID, FORBIDDEN, CONFLICT, RATE_LIMITED, INTERNAL, UNAVAILABLE);
        declare(openApi, OWNER_ENABLE, PathItem.HttpMethod.POST, true,
            PARAM_INVALID, FORBIDDEN, CONFLICT, RATE_LIMITED, INTERNAL, UNAVAILABLE);
        // API-027 受理结果查询。
        declare(openApi, OWNER_ACCEPTANCE, PathItem.HttpMethod.GET, false,
            PARAM_INVALID, FORBIDDEN, NOT_FOUND, RATE_LIMITED, INTERNAL, UNAVAILABLE);
    }

    /**
     * 重写一个操作的响应表：成功那条（返回类型派生的）按契约状态码就位，错误码按给定顺序追加。
     *
     * @param accepted 是否写接口：写接口把成功码从 200 挪到 202 并换成受理口径的描述
     * @param errorCodes 该接口在契约 1.4 里出现的错误码，按 HTTP 状态升序给出
     */
    private void declare(OpenAPI openApi, String path, PathItem.HttpMethod method, boolean accepted,
                         String... errorCodes) {
        Operation operation = operationOf(openApi, path, method);
        // 成功那条由返回类型派生，SpringDoc 默认放在 200（写接口也一样，HTTP 状态是运行期事实）。
        ApiResponses original = operation.getResponses();
        ApiResponse success = original == null ? null : original.remove(OK);
        if (success == null && original != null) {
            success = original.remove(ACCEPTED);
        }

        ApiResponses responses = new ApiResponses();
        if (success != null) {
            if (accepted) {
                success.setDescription(DESCRIPTION_ACCEPTED);
            }
            responses.addApiResponse(accepted ? ACCEPTED : OK, success);
        }
        for (String code : errorCodes) {
            responses.addApiResponse(code, new ApiResponse().description(describe(code)));
        }
        operation.setResponses(responses);
    }

    private Operation operationOf(OpenAPI openApi, String path, PathItem.HttpMethod method) {
        PathItem item = openApi.getPaths() == null ? null : openApi.getPaths().get(path);
        Operation operation = item == null ? null : item.readOperationsMap().get(method);
        if (operation == null) {
            throw new IllegalStateException("契约产物里缺少 " + method + " " + path
                + "：控制器被改名或删除会让货主档案契约缺一条，导出必须失败");
        }
        return operation;
    }

    private String describe(String code) {
        return switch (code) {
            case PARAM_INVALID -> DESCRIPTION_PARAM_INVALID;
            case FORBIDDEN -> DESCRIPTION_FORBIDDEN;
            case NOT_FOUND -> DESCRIPTION_NOT_FOUND;
            case CONFLICT -> DESCRIPTION_CONFLICT;
            case RATE_LIMITED -> DESCRIPTION_RATE_LIMITED;
            case INTERNAL -> DESCRIPTION_INTERNAL;
            case UNAVAILABLE -> DESCRIPTION_UNAVAILABLE;
            default -> throw new IllegalStateException("契约 1.4 没有错误码 " + code);
        };
    }
}
