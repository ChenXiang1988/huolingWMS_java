package com.huoling.wms.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约产物 {@code code/contracts/openapi.json} 与契约章 1.8／1.5 的对账（契约 1.11「契约一致」）。
 *
 * <p>为什么必须有一条这样的用例：产物是各端唯一来源（`01`「接口契约由服务端导出，各端只消费生成物」），
 * 路径写错、字段改名、枚举漂移都不会让任何一条业务用例变红——只有对着产物本身断言才会。
 * 本类读的是提交进仓库的那份产物（{@code code/contracts/openapi.json}），不是运行态，
 * 因此它同时挡住「改了控制器却忘了重导」与「手工改产物骗过前端」两种情形。
 *
 * <p>本类覆盖三组判定：
 * <ul>
 *   <li>契约 1.8 的条目级结构：8 组路径与方法、端分组、幂等键、分页与枚举；</li>
 *   <li>契约 1.5 的字段与枚举：列表／详情／受理票据／选择器条目的字段集，标识为字符串，状态变更动作闭集；</li>
 *   <li>响应码：四个写接口 202（不再是 200）、每个操作按契约 1.4「出现位置」列声明错误码、
 *       API-020 成功响应不带 {@code dataTimestamp}、产物里不出现已删除的 {@code ownerCode}／{@code name}。</li>
 * </ul>
 *
 * <p>最后三组是测试轮发现的 F-1 至 F-7 缺陷（{@code test-matrix.md} 5.1）的回归断言：
 * 这些缺陷都不会让任何业务用例变红，只有对着产物断言才拦得住。
 */
class OwnerContractDocumentTest {

    /** surefire 的工作目录是模块目录（{@code code/backend/wms-hub}），产物在仓库的 {@code code/contracts/}。 */
    private static final Path CONTRACT = Path.of("../../contracts/openapi.json")
        .toAbsolutePath().normalize();

    private static JsonNode document;

    @BeforeAll
    static void load() throws IOException {
        assertThat(Files.exists(CONTRACT))
            .as("契约产物缺失：%s（先跑 ContractExportTool 重导）", CONTRACT).isTrue();
        document = new ObjectMapper().readTree(Files.readString(CONTRACT));
        assertThat(document.path("paths").size()).as("产物必须包含接口路径").isPositive();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "GET,  /api/hub/owners",
        "GET,  /api/hub/owner-archives",
        "POST, /api/hub/owner-archives",
        "GET,  /api/hub/owner-archives/{ownerNo}",
        "PUT,  /api/hub/owner-archives/{ownerNo}",
        "POST, /api/hub/owner-archives/{ownerNo}/disable",
        "POST, /api/hub/owner-archives/{ownerNo}/enable",
        "GET,  /api/hub/owner-archives/acceptances/{acceptNo}",
    })
    @DisplayName("路径与方法逐条落在产物里（API-020 至 API-027）")
    void 路径与方法(String method, String path) {
        JsonNode operation = document.path("paths").path(path).path(method.toLowerCase());

        assertThat(operation.isMissingNode())
            .as("产物里缺 %s %s", method, path).isFalse();
        assertThat(operation.path("operationId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("八个操作全部落在运营端前缀下：货主端与单仓端不出现货主档案接口（D15）")
    void 端分组不混用() {
        List<String> foreign = new ArrayList<>();
        document.path("paths").fieldNames().forEachRemaining(path -> {
            boolean ownerArchive = path.contains("owner-archives") || path.equals("/api/hub/owners");
            if (ownerArchive && !path.startsWith("/api/hub/")) {
                foreign.add(path);
            }
        });

        assertThat(foreign).as("货主档案接口只出现在 /api/hub/**").isEmpty();
    }

    @Test
    @DisplayName("只有四个写接口要求幂等键，查询接口不要求（契约 1.3）")
    void 幂等键落在写接口上() {
        assertThat(headerNames(document.path("paths").path("/api/hub/owner-archives").path("post")))
            .contains("Idempotency-Key");
        assertThat(headerNames(document.path("paths").path("/api/hub/owner-archives/{ownerNo}")
            .path("put"))).contains("Idempotency-Key");
        assertThat(headerNames(document.path("paths").path(
            "/api/hub/owner-archives/{ownerNo}/disable").path("post"))).contains("Idempotency-Key");
        assertThat(headerNames(document.path("paths").path(
            "/api/hub/owner-archives/{ownerNo}/enable").path("post"))).contains("Idempotency-Key");

        assertThat(headerNames(document.path("paths").path("/api/hub/owners").path("get")))
            .as("查询接口天然幂等，不要求幂等键").doesNotContain("Idempotency-Key");
        assertThat(headerNames(document.path("paths").path("/api/hub/owner-archives").path("get")))
            .doesNotContain("Idempotency-Key");
        assertThat(headerNames(document.path("paths").path(
            "/api/hub/owner-archives/acceptances/{acceptNo}").path("get")))
            .doesNotContain("Idempotency-Key");
    }

    @Test
    @DisplayName("状态筛选是闭集 ACTIVE／DISABLED（契约 1.1「枚举」）")
    void 状态枚举闭集() {
        JsonNode status = queryParameter(document.path("paths").path("/api/hub/owner-archives")
            .path("get"), "status");

        assertThat(status.isMissingNode()).as("列表必须有 status 筛选").isFalse();
        assertThat(texts(status.path("schema").path("enum")))
            .containsExactly("ACTIVE", "DISABLED");
    }

    @Test
    @DisplayName("列表与详情字段逐项对齐契约 1.5；标识一律是字符串")
    void 列表与详情字段() {
        assertThat(fieldsOf("OwnerItem")).containsExactlyInAnyOrder(
            "ownerId", "ownerNo", "ownerName", "contactName", "contactPhone", "creditCode",
            "status", "version", "createdTime", "updateTime");

        assertThat(fieldsOf("OwnerDetail")).containsExactlyInAnyOrder(
            "ownerId", "ownerNo", "ownerName", "contactName", "contactPhone", "creditCode",
            "status", "version", "createdTime", "updateTime", "createdBy", "updatedBy",
            "lastStatusChange");

        assertThat(fieldsOf("OwnerStatusChangeView"))
            .as("状态变更留痕四段（契约 1.5 lastStatusChange）")
            .containsExactlyInAnyOrder("action", "reason", "operator", "changedAt");

        for (String identifier : List.of("ownerId", "ownerNo", "createdBy", "updatedBy")) {
            assertThat(schema("OwnerDetail").path("properties").path(identifier).path("type").asText())
                .as("标识 %s 必须是字符串：大整数在 JS 侧会丢精度（契约 1.5）", identifier)
                .isEqualTo("string");
        }
        assertThat(schema("OwnerItem").path("properties").path("version").path("type").asText())
            .as("版本号是整数，不是字符串").isEqualTo("integer");
    }

    @Test
    @DisplayName("受理票据与受理结果字段对齐契约 1.6／API-027")
    void 受理票据与结果字段() {
        assertThat(fieldsOf("OwnerTicket"))
            .containsExactlyInAnyOrder("acceptNo", "status", "replayed");
        assertThat(fieldsOf("OwnerAcceptanceView"))
            .containsExactlyInAnyOrder("acceptStatus", "result", "errorCode", "errorMessage");
    }

    @Test
    @DisplayName("选择器条目只暴露 ownerId、ownerNo、ownerName（API-020 响应）")
    void 选择器条目字段() {
        assertThat(fieldsOf("OwnerSelectorView"))
            .containsExactlyInAnyOrder("ownerId", "ownerNo", "ownerName");
        assertThat(fieldsOf("OwnerSelectorPage"))
            .containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
    }

    @Test
    @DisplayName("货主档案响应里不出现合同、费率与计费字段（D33：状态不表达合同是否生效）")
    void 不含合同字段() {
        List<String> offenders = new ArrayList<>();
        for (String name : List.of("OwnerItem", "OwnerDetail", "OwnerSelectorView", "OwnerTicket",
            "OwnerAcceptanceView", "OwnerStatusChangeView")) {
            schema(name).path("properties").fieldNames().forEachRemaining(field -> {
                String lower = field.toLowerCase();
                if (lower.contains("contract") || lower.contains("rate") || lower.contains("billing")
                    || lower.contains("price") || lower.contains("fee")) {
                    offenders.add(name + "." + field);
                }
            });
        }

        assertThat(offenders).as("货主档案契约不出现任何计费域字段").isEmpty();
    }

    @Test
    @DisplayName("错误明细结构是 details[].{field, reason}（契约 1.1「统一错误体」）")
    void 错误明细结构() {
        assertThat(fieldsOf("Issue")).containsExactlyInAnyOrder("field", "reason");
        assertThat(schema("OwnerResponseOwnerTicket").path("properties").path("details").path("type")
            .asText()).as("错误明细以数组承载").isEqualTo("array");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "GET,  /api/hub/owners,                              200 400 403 429 500 503",
        "GET,  /api/hub/owner-archives,                      200 400 403 429 500 503",
        "POST, /api/hub/owner-archives,                      202 400 403 409 429 500 503",
        "GET,  /api/hub/owner-archives/{ownerNo},            200 400 403 404 429 500 503",
        "PUT,  /api/hub/owner-archives/{ownerNo},            202 400 403 409 429 500 503",
        "POST, /api/hub/owner-archives/{ownerNo}/disable,    202 400 403 409 429 500 503",
        "POST, /api/hub/owner-archives/{ownerNo}/enable,     202 400 403 409 429 500 503",
        "GET,  /api/hub/owner-archives/acceptances/{acceptNo}, 200 400 403 404 429 500 503",
    })
    @DisplayName("成功码与错误码逐条声明：写接口 202，错误码按契约 1.4「出现位置」列分配（F-1、F-2）")
    void 响应码逐条声明(String method, String path, String expected) {
        assertThat(responseCodes(method, path))
            .as("%s %s 的响应码必须与契约 1.4／1.6 一致", method, path)
            .containsExactlyInAnyOrder(expected.split("\\s+"));
    }

    @Test
    @DisplayName("四个写接口的成功响应是受理票据：202 指向 OwnerResponse<OwnerTicket>，不再声明 200（F-1）")
    void 写接口成功响应是受理票据() {
        List<String> writes = List.of(
            "/api/hub/owner-archives|post",
            "/api/hub/owner-archives/{ownerNo}|put",
            "/api/hub/owner-archives/{ownerNo}/disable|post",
            "/api/hub/owner-archives/{ownerNo}/enable|post");

        for (String write : writes) {
            String[] parts = write.split("\\|");
            JsonNode responses = document.path("paths").path(parts[0]).path(parts[1]).path("responses");

            assertThat(responses.has("200"))
                .as("%s %s 不得再声明 200：写接口一律 202 受理", parts[1].toUpperCase(), parts[0])
                .isFalse();
            assertThat(schemaRefOf(responses.path("202")))
                .as("%s %s 的 202 必须指向受理票据信封", parts[1].toUpperCase(), parts[0])
                .isEqualTo("OwnerResponseOwnerTicket");
        }
    }

    @Test
    @DisplayName("建档与改档的请求体是自己的 schema：四项加 version，不指向身份模块或商品档案（F-3）")
    void 写请求体字段() {
        assertThat(schemaRefOf(requestBodyOf("post", "/api/hub/owner-archives")))
            .as("建档请求体必须是货主档案自己的 schema 名")
            .isEqualTo("OwnerCreateRequest");
        assertThat(fieldsOf("OwnerCreateRequest"))
            .as("建档请求体只有四项：编号、状态与版本由系统生成或置初值")
            .containsExactlyInAnyOrder("ownerName", "contactName", "contactPhone", "creditCode");

        assertThat(schemaRefOf(requestBodyOf("put", "/api/hub/owner-archives/{ownerNo}")))
            .as("改档请求体必须是货主档案自己的 schema 名")
            .isEqualTo("OwnerEditRequest");
        assertThat(fieldsOf("OwnerEditRequest"))
            .as("改档是全量替换：四项加版本号")
            .containsExactlyInAnyOrder("ownerName", "contactName", "contactPhone", "creditCode",
                "version");

        for (String path : List.of("/api/hub/owner-archives/{ownerNo}/disable",
            "/api/hub/owner-archives/{ownerNo}/enable")) {
            assertThat(fieldsOf(schemaRefOf(requestBodyOf("post", path))))
                .as("%s 的请求体是原因加版本号", path)
                .containsExactlyInAnyOrder("reason", "version");
        }
    }

    @Test
    @DisplayName("状态变更动作是闭集 CREATE／DISABLE／ENABLE，不含 EDIT（F-4、契约 1.5、`21` E-19）")
    void 状态变更动作闭集() {
        JsonNode action = dereference(
            schema("OwnerStatusChangeView").path("properties").path("action"));

        assertThat(texts(action.path("enum")))
            .as("改档不写状态留痕，对外不出现 EDIT")
            .containsExactly("CREATE", "DISABLE", "ENABLE");
    }

    @Test
    @DisplayName("选择器成功响应不带 dataTimestamp，且产物里没有已删除的 ownerCode／name 参数（F-5、F-7）")
    void 选择器成功响应与参数() {
        JsonNode operation = document.path("paths").path("/api/hub/owners").path("get");

        Set<String> successFields = new TreeSet<>();
        dereference(operation.path("responses").path("200")).path("properties").fieldNames()
            .forEachRemaining(successFields::add);
        assertThat(successFields)
            .as("API-020 读权威库：成功响应不得声明 dataTimestamp")
            .doesNotContain("dataTimestamp");
        assertThat(schemaRefOf(operation.path("responses").path("200")))
            .as("成功响应用不带 dataTimestamp 的信封")
            .isEqualTo("OwnerSelectorResponseOwnerSelectorPage");

        Set<String> queryNames = new TreeSet<>();
        operation.path("parameters").forEach(parameter -> {
            if ("query".equals(parameter.path("in").asText())) {
                queryNames.add(parameter.path("name").asText());
                assertThat(parameter.path("deprecated").asBoolean(false))
                    .as("查询参数 %s 不得标记废弃：旧参数必须从产物里消失",
                        parameter.path("name").asText())
                    .isFalse();
            }
        });
        assertThat(queryNames)
            .as("旧参数已删除，改名后的两个参数在列")
            .containsExactlyInAnyOrder("ownerNo", "ownerName", "sortBy", "sortDir", "cursor",
                "limit");
    }

    /** 一个操作的响应码集合。 */
    private static Set<String> responseCodes(String method, String path) {
        Set<String> codes = new TreeSet<>();
        document.path("paths").path(path).path(method.toLowerCase()).path("responses").fieldNames()
            .forEachRemaining(codes::add);
        return codes;
    }

    /** 一个操作的 {@code requestBody} 节点；没有请求体时是 missing node，交给调用方的断言去判空。 */
    private static JsonNode requestBodyOf(String method, String path) {
        return document.path("paths").path(path).path(method.toLowerCase()).path("requestBody");
    }

    /** 响应或内容节点里的 schema 引用名：{@code #/components/schemas/X} 取 {@code X}。 */
    private static String schemaRefOf(JsonNode node) {
        String ref = node.path("content").path("*/*").path("schema").path("$ref").asText("");
        if (ref.isEmpty()) {
            ref = node.path("content").path("application/json").path("schema").path("$ref")
                .asText("");
        }
        return ref.isEmpty() ? "" : ref.substring(ref.lastIndexOf('/') + 1);
    }

    /** 解引用：属性若是 {@code $ref} 就换成目标 schema，便于统一读枚举与字段。 */
    private static JsonNode dereference(JsonNode node) {
        String ref = node.path("$ref").asText("");
        return ref.isEmpty() ? node : schema(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static JsonNode schema(String name) {
        JsonNode schema = document.path("components").path("schemas").path(name);
        assertThat(schema.isMissingNode()).as("产物里缺 schema %s", name).isFalse();
        return schema;
    }

    private static Set<String> fieldsOf(String name) {
        Set<String> fields = new TreeSet<>();
        schema(name).path("properties").fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static Set<String> headerNames(JsonNode operation) {
        Set<String> names = new TreeSet<>();
        operation.path("parameters").forEach(parameter -> {
            if ("header".equals(parameter.path("in").asText())) {
                names.add(parameter.path("name").asText());
            }
        });
        return names;
    }

    private static JsonNode queryParameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if ("query".equals(parameter.path("in").asText())
                && name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
