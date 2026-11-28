package com.huoling.wms.hub;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开放接口文档的导出工具（01「接口契约由服务端导出，各端只消费生成物」）。
 *
 * <p><b>刻意不以 {@code Test}／{@code IT} 结尾</b>：普通构建（surefire 与 failsafe）都不会选中它，
 * 不会在每次编译时改写仓库里的产物。重导是显式动作，命令只有一条：
 *
 * <pre>
 * JAVA_HOME=&lt;jdk21&gt; mvn -f code/backend/pom.xml -pl wms-hub -am test \
 *     -Dtest=ContractExportTool -DfailIfNoTests=false
 * </pre>
 *
 * <p>取的是运行态的 {@code /v3/api-docs}：产物必须来自真实注册的控制器与注解，
 * 手写或补丁式的 JSON 无法保证与实际接口一致（tasks 3.13 的比对依据就是它）。
 *
 * <p>输出路径默认 {@code ../../contracts/openapi.json}（surefire 的工作目录是模块目录，即 {@code code/wms-hub}），
 * 可用 {@code -Dwms.contract.output=...} 覆盖。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractExportTool {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("导出 openapi.json 到 code/contracts/")
    void export() throws IOException {
        String raw = restTemplate.getForObject("http://localhost:" + port + "/v3/api-docs",
            String.class);
        assertThat(raw).as("/v3/api-docs 必须可访问").isNotBlank();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode document = mapper.readTree(raw);
        assertThat(document.path("paths").size()).as("导出物必须包含接口路径").isPositive();

        JsonNode siteFragment = readSiteFragment(mapper);
        ObjectNode normalized = normalize(mapper, merge(mapper, document, siteFragment));

        Path output = Path.of(System.getProperty("wms.contract.output",
            "../../contracts/openapi.json")).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        // 空数组统一写成 []（美化输出默认给 "[ ]"，与既有产物不一致，会让 diff 多一行噪声）。
        String text = writer().writeValueAsString(normalized).replace("\"tags\": [ ]", "\"tags\": []");
        Files.writeString(output, text + System.lineSeparator());
        System.out.println("已导出 " + document.path("paths").size() + " 条路径到 " + output);
    }

    /**
     * 读单仓端片段（由 {@code wms-site} 的 {@code ContractFragmentExportTool} 生成）。
     *
     * <p>缺失即失败、不做「只导出 Hub」的降级：那样会把 {@code /api/site/**} 的两条路径
     * 从产物里悄悄抹掉，而契约产物是全端唯一来源，缺一条就是契约缺一条。
     */
    private JsonNode readSiteFragment(ObjectMapper mapper) throws IOException {
        Path fragment = Path.of(System.getProperty("wms.contract.site-doc",
            "../wms-site/target/site-openapi.json")).toAbsolutePath().normalize();
        assertThat(Files.exists(fragment)).as("先导出单仓端片段：%s", fragment).isTrue();
        return mapper.readTree(Files.readString(fragment));
    }

    /**
     * 合并两个单元的文档：路径取并集，{@code components} 逐个映射取并集。
     *
     * <p>四个可启动单元互不依赖（AR-03），一个进程只能装配一个单元，所以产物天然是「片段 + 合并」。
     * 两边同名 schema 冲突时保留 Hub 的一份并告警：Hub 是运营端的权威源，
     * 单仓端的 DTO 若与 Hub 撞名，应当改名而不是靠合并顺序取胜。
     */
    private JsonNode merge(ObjectMapper mapper, JsonNode hubDoc, JsonNode siteDoc) {
        ObjectNode merged = mapper.createObjectNode();
        merged.put("openapi", hubDoc.path("openapi").asText("3.0.1"));
        merged.set("info", hubDoc.path("info"));

        ObjectNode paths = mapper.createObjectNode();
        hubDoc.path("paths").fields().forEachRemaining(entry -> paths.set(entry.getKey(), entry.getValue()));
        siteDoc.path("paths").fields().forEachRemaining(entry -> paths.set(entry.getKey(), entry.getValue()));
        merged.set("paths", paths);

        ObjectNode components = mapper.createObjectNode();
        mergeComponents(hubDoc.path("components"), components, mapper);
        mergeComponents(siteDoc.path("components"), components, mapper);
        merged.set("components", components);
        return merged;
    }

    private void mergeComponents(JsonNode source, ObjectNode target, ObjectMapper mapper) {
        source.fields().forEachRemaining(section -> {
            ObjectNode targetSection = target.has(section.getKey())
                ? (ObjectNode) target.get(section.getKey())
                : target.putObject(section.getKey());
            section.getValue().fields().forEachRemaining(entry -> {
                if (targetSection.has(entry.getKey())) {
                    return;
                }
                targetSection.set(entry.getKey(), entry.getValue());
            });
        });
    }

    /**
     * 把运行态文档收敛成提交物的形状：根键顺序固定为
     * {@code openapi / info / tags / paths / components}，标题用契约标题，去掉运行态地址。
     *
     * <p>不做这一步的话，每次重导都会带进随机端口（{@code servers[].url = http://localhost:56xxx}）
     * 与 springdoc 的默认标题，diff 里全是噪声，真正变化的接口反而看不出来。
     */
    private ObjectNode normalize(ObjectMapper mapper, JsonNode document) {
        ObjectNode root = mapper.createObjectNode();
        root.put("openapi", document.path("openapi").asText("3.0.1"));
        ObjectNode info = root.putObject("info");
        info.put("title", "货灵 WMS 三端接口契约");
        info.put("version", document.path("info").path("version").asText("v0"));
        root.putArray("tags");
        root.set("paths", document.path("paths"));
        if (document.has("components")) {
            root.set("components", document.path("components"));
        }
        return root;
    }

    /**
     * 产物风格沿用既有提交物：两空格缩进、数组逐行、字段名与值之间是 {@code ": "}（冒号前不留空格）。
     *
     * <p>Jackson 的默认美化输出用 {@code " : "}，直接落盘会把整份文件重写一遍——diff 里就看不到
     * 真正的接口变化了。这里只改这一个分隔符，其余与默认一致。
     */
    private ObjectWriter writer() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
            .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        return new ObjectMapper().writer(printer);
    }
}
