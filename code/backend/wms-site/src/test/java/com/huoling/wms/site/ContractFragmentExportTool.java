package com.huoling.wms.site;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单仓端接口文档片段导出（01「接口契约由服务端导出」）。
 *
 * <p>四个可启动单元互不依赖（AR-03），一个进程只能装配一个单元，所以契约产物由「片段 + 合并」两步完成：
 * 本工具导出 Site 片段到 <b>构建目录</b>（{@code target/site-openapi.json}，不进版本库），
 * 再由 {@code wms-hub} 的 {@code ContractExportTool} 把它并进 {@code code/contracts/openapi.json}。
 *
 * <p><b>刻意不以 {@code Test}／{@code IT} 结尾</b>：普通构建不会选中它，重导是显式动作：
 *
 * <pre>
 * JAVA_HOME=&lt;jdk21&gt; mvn -f code/backend/pom.xml -pl wms-site -am test \
 *     -Dtest=ContractFragmentExportTool -DfailIfNoTests=false
 * </pre>
 */
@SpringBootTest(classes = SiteApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractFragmentExportTool {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("导出单仓端接口文档片段到 target/")
    void export() throws IOException {
        String raw = restTemplate.getForObject("http://localhost:" + port + "/v3/api-docs",
            String.class);
        assertThat(raw).as("/v3/api-docs 必须可访问").isNotBlank();

        Path output = Path.of(System.getProperty("wms.contract.site-output",
            "target/site-openapi.json")).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        // 片段不经美化：合并方读的是 JSON 结构，格式由合并后统一输出。
        Files.writeString(output, new ObjectMapper().readTree(raw).toString());
        System.out.println("已导出单仓端接口文档片段到 " + output);
    }
}
