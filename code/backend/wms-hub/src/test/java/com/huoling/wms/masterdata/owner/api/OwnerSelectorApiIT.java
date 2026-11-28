package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.owner.application.OwnerCommandService;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 货主选择器改造后的契约验收（tasks 1.12、契约 1.8 API-020 的 **BREAKING** 条目）。
 *
 * <p>五件事逐条落地：数据源换成货主档案、只返回可用货主、参数改名为 {@code ownerNo} /
 * {@code ownerName}、响应补 {@code ownerNo}、权限点换成 {@code owner.hub.read}；
 * 旧参数传入即 400 并指出新名。停用后不再出现与「无档案返回空数组」也在本类。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerSelectorApiIT extends OwnerApiTestSupport {

    @Test
    @DisplayName("只返回可用货主：停用档不出现，条目含 ownerId、ownerNo、ownerName")
    void 只返回可用货主() {
        String token = hubToken(createHubAdminAccount());
        String tag = "选择器货主-" + nextId();
        OwnerCommandService.OwnerResultView active = prepareOwner(tag + "-可用", uniqueCreditCode());
        OwnerCommandService.OwnerResultView disabled = prepareDisabledOwner(tag + "-停用");

        List<Map<String, Object>> items = selector(token, "ownerName=" + encode(tag));

        assertThat(items).extracting(item -> item.get("ownerNo"))
            .containsExactly(active.ownerNo())
            .doesNotContain(disabled.ownerNo());
        assertThat(items.get(0)).containsOnlyKeys("ownerId", "ownerNo", "ownerName");
    }

    @Test
    @DisplayName("ownerNo 精确检索与 ownerName 模糊检索同时给出取交集")
    void 编号精确与名称模糊() {
        String token = hubToken(createHubAdminAccount());
        String tag = "选择器检索-" + nextId();
        OwnerCommandService.OwnerResultView first = prepareOwner(tag + "-华东", uniqueCreditCode());
        prepareOwner(tag + "-华南", uniqueCreditCode());

        assertThat(selector(token, "ownerNo=" + first.ownerNo()))
            .extracting(item -> item.get("ownerNo")).containsExactly(first.ownerNo());
        assertThat(selector(token, "ownerName=" + encode(tag))).hasSize(2);
        assertThat(selector(token, "ownerNo=" + first.ownerNo() + "&ownerName=" + encode("华南")))
            .as("两个条件取交集").isEmpty();
    }

    @Test
    @DisplayName("旧参数 ownerCode 与 name：400 PARAM_INVALID，details[].field 指出旧名")
    void 旧参数被拒() {
        String token = hubToken(createHubAdminAccount());

        assertFieldError(call(HttpMethod.GET, "/api/hub/owners?ownerCode=1", token, null, null),
            "ownerCode");
        assertFieldError(call(HttpMethod.GET, "/api/hub/owners?name=" + encode("华东"), token, null,
            null), "name");
    }

    @Test
    @DisplayName("停用后选择器不再返回该货主（验收：停用 → 选择器不返回）")
    void 停用后不返回() {
        String token = hubToken(createHubAdminAccount());
        String tag = "停用选择器-" + nextId();
        OwnerCommandService.OwnerResultView owner = prepareOwner(tag, uniqueCreditCode());
        assertThat(selector(token, "ownerName=" + encode(tag))).hasSize(1);

        String disableAcceptNo = data(call(HttpMethod.POST,
            "/api/hub/owner-archives/" + owner.ownerNo() + "/disable", token, newKey("disable"),
            "{\"reason\":\"选择器用例：停用\",\"version\":0}")).get("acceptNo").toString();
        registerAcceptNo(disableAcceptNo);
        awaitAcceptance(disableAcceptNo, token, "SUCCEEDED");

        assertThat(selector(token, "ownerName=" + encode(tag)))
            .as("停用货主不再出现在选择器里").isEmpty();
    }

    @Test
    @DisplayName("权限点换为 owner.hub.read：只持该点的账号可查（无 archive.hub.create）")
    void 只读账号可查() {
        String tag = "选择器只读-" + nextId();
        prepareOwner(tag, uniqueCreditCode());
        String token = hubToken(createOwnerReadOnlyHubAccount());

        assertThat(selector(token, "ownerName=" + encode(tag))).hasSize(1);
    }

    @Test
    @DisplayName("无可用货主时返回空数组，不伪造条目")
    void 空数组() {
        String token = hubToken(createHubAdminAccount());

        Map<String, Object> data = data(call(HttpMethod.GET,
            "/api/hub/owners?ownerNo=OW999999", token, null, null));

        assertThat(data).containsOnlyKeys("items", "nextCursor", "hasMore");
        assertThat((List<?>) data.get("items")).isEmpty();
        assertThat(data.get("hasMore")).isEqualTo(Boolean.FALSE);
        assertThat(data.get("nextCursor")).isNull();
    }

    @Test
    @DisplayName("键集分页：limit 与游标前进，翻页不重复")
    void 分页() {
        String token = hubToken(createHubAdminAccount());
        String tag = "选择器分页-" + nextId();
        prepareOwner(tag + "-A", uniqueCreditCode());
        prepareOwner(tag + "-B", uniqueCreditCode());

        Map<String, Object> firstPage = data(call(HttpMethod.GET,
            "/api/hub/owners?ownerName=" + encode(tag) + "&limit=1", token, null, null));
        assertThat(firstPage.get("hasMore")).isEqualTo(Boolean.TRUE);
        String cursor = firstPage.get("nextCursor").toString();

        Map<String, Object> secondPage = data(call(HttpMethod.GET,
            "/api/hub/owners?ownerName=" + encode(tag) + "&limit=1&cursor=" + encode(cursor),
            token, null, null));

        assertThat(secondPage.get("hasMore")).isEqualTo(Boolean.FALSE);
        assertThat(((List<?>) firstPage.get("items")).get(0))
            .as("两页不重复").isNotEqualTo(((List<?>) secondPage.get("items")).get(0));
    }

    @Test
    @DisplayName("排序白名单外与 limit 越界：400 PARAM_INVALID 并指出字段")
    void 参数边界() {
        String token = hubToken(createHubAdminAccount());

        assertFieldError(call(HttpMethod.GET, "/api/hub/owners?sortBy=creditCode", token, null, null),
            "sortBy");
        assertFieldError(call(HttpMethod.GET, "/api/hub/owners?sortDir=up", token, null, null),
            "sortDir");
        assertFieldError(call(HttpMethod.GET, "/api/hub/owners?limit=101", token, null, null),
            "limit");
    }

    @Test
    @DisplayName("数据域为指定列表：只返回域内可用货主")
    void 指定列表只看域内() {
        String tag = "选择器域-" + nextId();
        OwnerCommandService.OwnerResultView inside = prepareOwner(tag + "-域内", uniqueCreditCode());
        OwnerCommandService.OwnerResultView outside = prepareOwner(tag + "-域外", uniqueCreditCode());
        String token = hubToken(createScopedHubAccount(List.of(Long.parseLong(inside.ownerId()))));

        List<Map<String, Object>> items = selector(token, "ownerName=" + encode(tag));

        assertThat(items).extracting(item -> item.get("ownerNo"))
            .contains(inside.ownerNo())
            .doesNotContain(outside.ownerNo());
    }

    private List<Map<String, Object>> selector(String token, String query) {
        Map<String, Object> data = data(call(HttpMethod.GET, "/api/hub/owners?" + query, token,
            null, null));
        assertThat(data).doesNotContainKey("dataTimestamp");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        return items;
    }

    private void assertFieldError(ResponseEntity<Map<String, Object>> response, String field) {
        assertThat(response.getStatusCode())
            .as("期望 400，实际 %s，响应体 %s", response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(OwnerErrorCodes.PARAM_INVALID);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details =
            (List<Map<String, Object>>) response.getBody().get("details");
        assertThat(details).isNotNull();
        assertThat(details).extracting(detail -> detail.get("field")).contains(field);
    }

    /**
     * 查询参数原样拼进 URL：由 {@code TestRestTemplate} 按 UTF-8 编码一次。
     *
     * <p>先 {@code URLEncoder.encode} 会被再编码一次（{@code %E5} → {@code %25E5}），
     * 服务端按字面量匹配，中文名称一条都命中不了。
     */
    private String encode(String value) {
        return value;
    }

    private String newKey(String scene) {
        String key = "it-owner-selector-" + scene + "-" + nextId();
        registerIdempotencyKey(key);
        return key;
    }
}
