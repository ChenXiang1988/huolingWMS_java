package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.owner.application.OwnerCommandService;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主档案接口的数据域与端校验（tasks 1.9 至 1.12 的鉴权面，契约 1.1、1.2 与 INV-19）。
 *
 * <p>三类数据域各自的可见范围，加上两类越权：
 * <ul>
 *   <li>域为「全部」：看全量、可建档；</li>
 *   <li>域为「指定列表」：只看域内，域外按不存在处理，建档被拒；</li>
 *   <li>域为空（指定列表无项）：直接 403，不回落成全量（OI-08）；</li>
 *   <li>货主端令牌：API-020 至 API-027 全部 403 {@code FORBIDDEN_OWNER}；</li>
 *   <li>缺功能权限：403 {@code AUTHZ_2001}，不返回空数据。</li>
 * </ul>
 */
@SpringBootTest(classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerArchiveDataScopeIT extends OwnerApiTestSupport {

    @Test
    @DisplayName("域为全部：可查全量、可建档")
    void 全域可见() {
        String token = hubToken(createHubAdminAccount());
        OwnerCommandService.OwnerResultView owner = prepareOwner("全域货主-" + nextId(),
            uniqueCreditCode());

        Map<String, Object> detail = data(call(HttpMethod.GET,
            "/api/hub/owner-archives/" + owner.ownerNo(), token, null, null));
        assertThat(detail.get("ownerNo")).isEqualTo(owner.ownerNo());

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, newKey("scope-create"),
            "{\"ownerName\":\"全域建档货主\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\","
                + "\"creditCode\":\"" + uniqueCreditCode() + "\"}");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = data(created).get("acceptNo").toString();
        registerAcceptNo(acceptNo);
        registerOwnerRow(Long.parseLong(
            resultOf(awaitAcceptance(acceptNo, token, "SUCCEEDED")).get("ownerId").toString()));
    }

    @Test
    @DisplayName("域为指定列表：只看域内；域外详情 404；建档 403")
    void 指定列表可见域内() {
        OwnerCommandService.OwnerResultView inside = prepareOwner("域内货主-" + nextId(),
            uniqueCreditCode());
        OwnerCommandService.OwnerResultView outside = prepareOwner("域外货主-" + nextId(),
            uniqueCreditCode());
        long insideId = Long.parseLong(inside.ownerId());
        String token = hubToken(createScopedHubAccount(List.of(insideId)));

        Map<String, Object> detail = data(call(HttpMethod.GET,
            "/api/hub/owner-archives/" + inside.ownerNo(), token, null, null));
        assertThat(detail.get("ownerNo")).isEqualTo(inside.ownerNo());

        ResponseEntity<Map<String, Object>> outOfScope = call(HttpMethod.GET,
            "/api/hub/owner-archives/" + outside.ownerNo(), token, null, null);
        assertThat(outOfScope.getStatusCode())
            .as("域外与不存在同一响应，不泄露存在性")
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outOfScope.getBody().get("code")).isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);

        Map<String, Object> list = data(call(HttpMethod.GET, "/api/hub/owner-archives", token,
            null, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
        assertThat(items).extracting(item -> item.get("ownerNo"))
            .contains(inside.ownerNo())
            .doesNotContain(outside.ownerNo());

        ResponseEntity<Map<String, Object>> create = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, newKey("scoped-create"),
            "{\"ownerName\":\"域内账号建档\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\","
                + "\"creditCode\":\"" + uniqueCreditCode() + "\"}");
        assertThat(create.getStatusCode())
            .as("建档要求全域：指定列表账号不得建档")
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create.getBody().get("code")).isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
    }

    @Test
    @DisplayName("域外写目标按不存在处理：受理结果 NOT_FOUND_OWNER，该受理单在域内账号也查不到")
    void 域外写目标按不存在处理() {
        OwnerCommandService.OwnerResultView inside = prepareOwner("域内写货主-" + nextId(),
            uniqueCreditCode());
        OwnerCommandService.OwnerResultView outside = prepareOwner("域外写货主-" + nextId(),
            uniqueCreditCode());
        String scopedToken = hubToken(createScopedHubAccount(
            List.of(Long.parseLong(inside.ownerId()))));

        // 入口只做字段级校验，存在性与数据域留给受理后判定（design「业务校验的时点」）
        ResponseEntity<Map<String, Object>> submitted = call(HttpMethod.POST,
            "/api/hub/owner-archives/" + outside.ownerNo() + "/disable", scopedToken,
            newKey("scope-disable"), "{\"reason\":\"域外停用\",\"version\":0}");
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = data(submitted).get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        // 用全域账号读受理结果：结论是「目标不存在」，而不是越权或成功
        String allToken = hubToken(createHubAdminAccount());
        assertThat(awaitAcceptance(acceptNo, allToken, "FAILED").get("errorCode"))
            .as("域外目标与不存在同一响应，不泄露存在性")
            .isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);
        assertThat(jdbc.queryForObject("select status from wms_owner where owner_no = ?",
            String.class, outside.ownerNo())).as("被拒的域外停用不改档案").isEqualTo("ACTIVE");

        // INV-06 的第四处：受理查询同样按货主域收窄，域外受理单与不存在同一响应
        ResponseEntity<Map<String, Object>> hidden = call(HttpMethod.GET,
            "/api/hub/owner-archives/acceptances/" + acceptNo, scopedToken, null, null);
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(hidden.getBody().get("code")).isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);
    }

    @Test
    @DisplayName("受理查询按货主域收窄：域内受理单可见，域外账号 404，全域账号可见")
    void 受理查询按域收窄() {
        OwnerCommandService.OwnerResultView inside = prepareOwner("受理域内货主-" + nextId(),
            uniqueCreditCode());
        OwnerCommandService.OwnerResultView other = prepareOwner("受理域外货主-" + nextId(),
            uniqueCreditCode());
        String insideToken = hubToken(createScopedHubAccount(
            List.of(Long.parseLong(inside.ownerId()))));

        ResponseEntity<Map<String, Object>> submitted = call(HttpMethod.POST,
            "/api/hub/owner-archives/" + inside.ownerNo() + "/disable", insideToken,
            newKey("scope-acceptance"), "{\"reason\":\"域内停用\",\"version\":0}");
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = data(submitted).get("acceptNo").toString();
        registerAcceptNo(acceptNo);

        assertThat(awaitAcceptance(acceptNo, insideToken, "SUCCEEDED").get("result"))
            .as("目标货主在域内：受理单可见且有结果摘要").isNotNull();

        String outsideToken = hubToken(createScopedHubAccount(
            List.of(Long.parseLong(other.ownerId()))));
        ResponseEntity<Map<String, Object>> hidden = call(HttpMethod.GET,
            "/api/hub/owner-archives/acceptances/" + acceptNo, outsideToken, null, null);
        assertThat(hidden.getStatusCode()).as("域外账号看不到别人货主的受理单")
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(hidden.getBody().get("code")).isEqualTo(OwnerErrorCodes.NOT_FOUND_OWNER);

        String allToken = hubToken(createHubAdminAccount());
        assertThat(data(call(HttpMethod.GET, "/api/hub/owner-archives/acceptances/" + acceptNo,
            allToken, null, null)).get("acceptStatus")).as("全域账号可见").isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("域为空：不回落成全量（服务层直接拒绝，读写都不放行）")
    void 空域拒绝() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("空域货主-" + nextId(),
            uniqueCreditCode());

        // 为什么在服务层验：身份模块的 RoleScope 在写入时就不允许「指定列表但没有任何标识」，
        // 所以库里造不出空域账号；空域是 DataScopeMerger 与 OwnerScope 侧的防御路径，
        // 只能在这一层证明它确实拒绝而不是回落成全量（OI-08、05 第 3 节）。
        assertThatThrownBy(() -> ownerCommandService.disable(owner.ownerNo(), OwnerScope.of(Set.of()),
            0L, "空域尝试", MIGRATION_OPERATOR))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
        assertThatThrownBy(() -> ownerCommandService.edit(owner.ownerNo(), OwnerScope.of(Set.of()), 0L,
            fields("空域改名"), MIGRATION_OPERATOR))
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
        assertThat(jdbc.queryForObject("select status from wms_owner where owner_no = ?",
            String.class, owner.ownerNo())).as("空域拒绝后状态不变").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("未配置货主域按全部处理：不因缺配置而看不到数据（05 第 3 节）")
    void 未配置域按全部处理() {
        // 只持 owner.hub.read、角色上没有任何数据域行：未配置的维度按「全部」处理
        String token = hubToken(createOwnerReadOnlyHubAccount());
        OwnerCommandService.OwnerResultView owner = prepareOwner("未配置域货主-" + nextId(),
            uniqueCreditCode());

        Map<String, Object> detail = data(call(HttpMethod.GET,
            "/api/hub/owner-archives/" + owner.ownerNo(), token, null, null));
        assertThat(detail.get("ownerNo")).isEqualTo(owner.ownerNo());
    }

    @Test
    @DisplayName("货主端令牌访问 API-020 至 API-027：全部 403 FORBIDDEN_OWNER")
    void 货主端令牌全拒() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("越权货主-" + nextId(),
            uniqueCreditCode());
        String token = ownerToken(createOwnerAccount(Long.parseLong(owner.ownerId())));
        String ownerNo = owner.ownerNo();

        assertForbidden(call(HttpMethod.GET, "/api/hub/owners", token, null, null));
        assertForbidden(call(HttpMethod.GET, "/api/hub/owner-archives", token, null, null));
        assertForbidden(call(HttpMethod.GET, "/api/hub/owner-archives/" + ownerNo, token, null, null));
        assertForbidden(call(HttpMethod.GET, "/api/hub/owner-archives/acceptances/ARC000000000000000001",
            token, null, null));
        assertForbidden(call(HttpMethod.POST, "/api/hub/owner-archives", token, "owner-token-create",
            "{\"ownerName\":\"越权建档\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\","
                + "\"creditCode\":\"" + uniqueCreditCode() + "\"}"));
        assertForbidden(call(HttpMethod.PUT, "/api/hub/owner-archives/" + ownerNo, token,
            "owner-token-edit", "{\"ownerName\":\"越权改档\",\"contactName\":\"张三\","
                + "\"contactPhone\":\"021-62345678\",\"creditCode\":\"" + uniqueCreditCode()
                + "\",\"version\":0}"));
        assertForbidden(call(HttpMethod.POST, "/api/hub/owner-archives/" + ownerNo + "/disable",
            token, "owner-token-disable", "{\"reason\":\"越权停用\",\"version\":0}"));
        assertForbidden(call(HttpMethod.POST, "/api/hub/owner-archives/" + ownerNo + "/enable",
            token, "owner-token-enable", "{\"reason\":\"越权启用\",\"version\":0}"));
    }

    @Test
    @DisplayName("缺 owner.hub.* 权限点的运营端账号：403 AUTHZ_2001，不返回空数据")
    void 缺功能权限() {
        String token = hubToken(createHubAccountWithoutOwnerPermissions());

        ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET, "/api/hub/owner-archives",
            token, null, null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(list.getBody().get("code")).as("身份模块的 AUTHZ_2001 走数字码")
            .isEqualTo(2001);
        assertThat(list.getBody().get("data")).as("无权限不得返回空数据冒充成功").isNull();

        ResponseEntity<Map<String, Object>> create = call(HttpMethod.POST,
            "/api/hub/owner-archives", token, newKey("noperm-create"),
            "{\"ownerName\":\"无权建档\",\"contactName\":\"张三\",\"contactPhone\":\"021-62345678\","
                + "\"creditCode\":\"" + uniqueCreditCode() + "\"}");
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** 端不符一律 403 + FORBIDDEN_OWNER（契约 1.1「端与令牌的强校验」）。 */
    private void assertForbidden(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode())
            .as("期望 403，实际 %s，响应体 %s", response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
    }

    private String newKey(String scene) {
        String key = "it-owner-scope-" + scene + "-" + nextId();
        registerIdempotencyKey(key);
        return key;
    }
}
