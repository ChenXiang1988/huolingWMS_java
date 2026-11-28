package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.owner.application.OwnerCommandService;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 货主档案查询接口的契约验收（tasks 1.9）：四类筛选与组合、排序白名单、键集分页不重不漏、
 * 详情字段与错误码，以及「成功响应不带 dataTimestamp」。
 *
 * <p>用同一前缀的名称把用例自己造的数据圈出来：库里可能还有别的用例或人工数据，
 * 断言「只返回这几条」必须先按前缀筛选，否则会随执行顺序变红。
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerArchiveQueryIT extends OwnerApiTestSupport {

    @Test
    @DisplayName("四类筛选各一条并取交集：编号精确、名称模糊、信用代码精确、状态")
    void 四类筛选() {
        String token = hubToken(createHubAdminAccount());
        String tag = "筛选货主-" + nextId();
        OwnerCommandService.OwnerResultView first = prepareOwner(tag + "-华东", uniqueCreditCode());
        OwnerCommandService.OwnerResultView second = prepareOwner(tag + "-华南", uniqueCreditCode());
        OwnerCommandService.OwnerResultView disabled = prepareDisabledOwner(tag + "-停用");
        String creditCode = jdbc.queryForObject("select credit_code from wms_owner where owner_no = ?",
            String.class, first.ownerNo());

        List<Map<String, Object>> byNo = list(token, "ownerNo=" + first.ownerNo());
        assertThat(byNo).extracting(item -> item.get("ownerNo"))
            .containsExactly(first.ownerNo());

        List<Map<String, Object>> byName = list(token, "ownerName=" + encode(tag));
        assertThat(byName).extracting(item -> item.get("ownerNo"))
            .containsExactlyInAnyOrder(first.ownerNo(), second.ownerNo(), disabled.ownerNo());

        List<Map<String, Object>> byCredit = list(token, "creditCode=" + creditCode);
        assertThat(byCredit).extracting(item -> item.get("ownerNo")).containsExactly(first.ownerNo());

        List<Map<String, Object>> byStatus = list(token, "ownerName=" + encode(tag) + "&status=DISABLED");
        assertThat(byStatus).extracting(item -> item.get("ownerNo")).containsExactly(disabled.ownerNo());

        List<Map<String, Object>> combined = list(token,
            "ownerName=" + encode(tag) + "&status=ACTIVE&ownerNo=" + first.ownerNo());
        assertThat(combined).extracting(item -> item.get("ownerNo")).containsExactly(first.ownerNo());
    }

    @Test
    @DisplayName("列表字段逐字对齐契约：含版本号与审计时间，不带 dataTimestamp")
    void 列表字段() {
        String token = hubToken(createHubAdminAccount());
        String tag = "字段货主-" + nextId();
        prepareOwner(tag, uniqueCreditCode());

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
            "/api/hub/owner-archives?ownerName=" + encode(tag), token, null, null);

        Map<String, Object> data = data(response);
        assertThat(data).containsOnlyKeys("items", "nextCursor", "hasMore");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsOnlyKeys("ownerId", "ownerNo", "ownerName", "contactName",
            "contactPhone", "creditCode", "status", "version", "createdTime", "updateTime");
        assertThat(items.get(0).get("ownerId")).as("标识是十进制字符串").isInstanceOf(String.class);
        assertThat(items.get(0).get("version")).isInstanceOf(Number.class);
        assertThat(items.get(0).get("createdTime")).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("键集分页：翻页期间新增档案不跳行、不重复")
    void 翻页不跳行() {
        String token = hubToken(createHubAdminAccount());
        String tag = "分页货主-" + nextId();
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            expected.add(prepareOwner(tag + "-" + index, uniqueCreditCode()).ownerNo());
        }

        Map<String, Object> firstPage = data(call(HttpMethod.GET,
            "/api/hub/owner-archives?ownerName=" + encode(tag) + "&limit=2", token, null, null));
        List<String> seen = new ArrayList<>(ownerNos(firstPage));
        assertThat(firstPage.get("hasMore")).isEqualTo(Boolean.TRUE);

        // 翻页期间新增一条：它排在已翻过的锚点之后，不影响后续页的续翻
        expected.add(prepareOwner(tag + "-新增", uniqueCreditCode()).ownerNo());

        String cursor = firstPage.get("nextCursor").toString();
        Map<String, Object> secondPage = data(call(HttpMethod.GET,
            "/api/hub/owner-archives?ownerName=" + encode(tag) + "&limit=2&cursor="
                + encode(cursor), token, null, null));
        seen.addAll(ownerNos(secondPage));

        String secondCursor = secondPage.get("nextCursor") == null ? null
            : secondPage.get("nextCursor").toString();
        if (secondCursor != null) {
            seen.addAll(ownerNos(data(call(HttpMethod.GET,
                "/api/hub/owner-archives?ownerName=" + encode(tag) + "&limit=2&cursor="
                    + encode(secondCursor), token, null, null))));
        }

        assertThat(seen).as("翻页期间不重复").doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).as("首两页必须覆盖前两条，新增项不得挤掉它们")
            .contains(expected.get(0), expected.get(1));
        assertThat(seen.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("按名称排序与按更新时间排序都可用；游标与排序参数不一致按参数错误拒绝")
    void 排序与游标() {
        String token = hubToken(createHubAdminAccount());
        String tag = "排序货主-" + nextId();
        prepareOwner(tag + "-B", uniqueCreditCode());
        prepareOwner(tag + "-A", uniqueCreditCode());

        List<Map<String, Object>> byName = list(token,
            "ownerName=" + encode(tag) + "&sortBy=ownerName&sortDir=asc");
        assertThat(byName.stream().map(item -> item.get("ownerName").toString()).toList())
            .as("按名称升序").isSorted();

        List<Map<String, Object>> byUpdate = list(token,
            "ownerName=" + encode(tag) + "&sortBy=updateTime&sortDir=desc");
        assertThat(byUpdate).hasSize(2);

        Map<String, Object> firstPage = data(call(HttpMethod.GET,
            "/api/hub/owner-archives?ownerName=" + encode(tag) + "&limit=1&sortBy=ownerName",
            token, null, null));
        String cursor = firstPage.get("nextCursor").toString();
        ResponseEntity<Map<String, Object>> mismatch = call(HttpMethod.GET,
            "/api/hub/owner-archives?ownerName=" + encode(tag) + "&sortBy=updateTime&cursor="
                + encode(cursor), token, null, null);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody().get("code")).isEqualTo(OwnerErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("查询参数边界：limit 越界、排序白名单外、状态枚举外一律 400 并指出字段")
    void 参数边界() {
        String token = hubToken(createHubAdminAccount());

        assertFieldError(call(HttpMethod.GET, "/api/hub/owner-archives?limit=101", token, null, null),
            "limit");
        assertFieldError(call(HttpMethod.GET, "/api/hub/owner-archives?limit=0", token, null, null),
            "limit");
        assertFieldError(call(HttpMethod.GET, "/api/hub/owner-archives?sortBy=creditCode", token,
            null, null), "sortBy");
        assertFieldError(call(HttpMethod.GET, "/api/hub/owner-archives?sortDir=up", token, null,
            null), "sortDir");
        assertFieldError(call(HttpMethod.GET, "/api/hub/owner-archives?status=PENDING", token, null,
            null), "status");
    }

    @Test
    @DisplayName("详情：命中返回三段留痕；编号格式非法 400；不存在 404")
    void 详情() {
        String token = hubToken(createHubAdminAccount());
        OwnerCommandService.OwnerResultView owner = prepareOwner("详情货主-" + nextId(),
            uniqueCreditCode());

        Map<String, Object> detail = data(call(HttpMethod.GET,
            "/api/hub/owner-archives/" + owner.ownerNo(), token, null, null));
        assertThat(detail.get("ownerNo")).isEqualTo(owner.ownerNo());
        assertThat(detail).containsKeys("createdBy", "updatedBy", "lastStatusChange");

        ResponseEntity<Map<String, Object>> badNo = call(HttpMethod.GET,
            "/api/hub/owner-archives/OWabc", token, null, null);
        assertThat(badNo.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badNo.getBody().get("code")).isEqualTo(OwnerErrorCodes.PARAM_INVALID);

        ResponseEntity<Map<String, Object>> missing = call(HttpMethod.GET,
            "/api/hub/owner-archives/OW999999", token, null, null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code")).isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);
    }

    @Test
    @DisplayName("按最后修改时间过滤：闭区间内的留下，区间外的排除")
    void 更新时间闭区间() {
        String token = hubToken(createHubAdminAccount());
        String tag = "时间货主-" + nextId();
        OwnerCommandService.OwnerResultView owner = prepareOwner(tag, uniqueCreditCode());

        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        // 触发器要求版本逐次递增一：直接改时间也要同步推进版本，否则库层会拒绝。
        jdbc.update("update wms_owner set updated_at = ?, version = version + 1 where owner_no = ?",
            past, owner.ownerNo());

        assertThat(list(token, "ownerName=" + encode(tag) + "&updatedFrom="
            + encode(past.plusHours(1).toString()))).isEmpty();
        assertThat(list(token, "ownerName=" + encode(tag) + "&updatedFrom="
            + encode(past.minusHours(1).toString()))).hasSize(1);
        assertThat(list(token, "ownerName=" + encode(tag) + "&updatedTo="
            + encode(past.minusHours(1).toString()))).isEmpty();
    }

    private List<Map<String, Object>> list(String token, String query) {
        Map<String, Object> data = data(call(HttpMethod.GET,
            "/api/hub/owner-archives?" + query, token, null, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        return items;
    }

    @SuppressWarnings("unchecked")
    private List<String> ownerNos(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> item.get("ownerNo").toString())
            .toList();
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
     * 查询参数原样拼进 URL：交给 {@code TestRestTemplate} 按 UTF-8 编码一次。
     *
     * <p>不要在这里先 {@code URLEncoder.encode}：RestTemplate 的 URI 构造按组件再编码一次，
     * 中文与 ISO 时间里的冒号会被编成 {@code %25E7%25AD%9B}、{@code %253A}，
     * 服务端收到的是编码后的字面量，筛选自然一条都命中不了。
     */
    private String encode(String value) {
        return value;
    }
}
