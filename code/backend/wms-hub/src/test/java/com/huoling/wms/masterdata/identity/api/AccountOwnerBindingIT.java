package com.huoling.wms.masterdata.identity.api;

import com.huoling.wms.masterdata.owner.application.OwnerCommandService;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import com.huoling.wms.masterdata.owner.support.OwnerApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主端账号的绑定约束与停用后失效（tasks 1.15；契约 1.7、spec「货主端账号必须绑定存在的货主」）。
 *
 * <p>走真 HTTP + 真库：入口的 400 要经过过滤器、权限拦截器与异常映射；
 * 登录拒绝要经过 {@code AuthService} 与货主状态查询；旧令牌失效要经过第一道门的第五步；
 * 绑定不可改还要落到库层触发器。
 */
@SpringBootTest(classes = com.huoling.wms.hub.HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountOwnerBindingIT extends OwnerApiTestSupport {

    private static final String PASSWORD = "Owner@12345";

    private final Set<Long> createdAccounts = new LinkedHashSet<>();

    private String adminToken;

    @BeforeEach
    void prepareAdmin() {
        adminToken = hubToken(createPermissionAdminAccount());
    }

    @AfterEach
    void cleanAccounts() {
        for (Long accountId : createdAccounts) {
            jdbc.update("delete from wms_account_role where account_id = ?", accountId);
            jdbc.update("delete from wms_account where id = ?", accountId);
        }
        createdAccounts.clear();
    }

    @Test
    @DisplayName("client=OWNER 未给 ownerId、货主不存在、client=HUB 给了 ownerId：一律 400 且不建账号")
    void 开通校验货主绑定() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("开通校验货主-" + nextId(),
            uniqueCreditCode());
        String accountName = "bind-check-" + nextId();

        ResponseEntity<Map<String, Object>> missingOwner = call(HttpMethod.POST, "/hub/api/accounts",
            adminToken, "key-" + nextId(), createBody(accountName, "OWNER", null, null));
        assertThat(missingOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(missingOwner)).isEqualTo(9001);

        ResponseEntity<Map<String, Object>> unknownOwner = call(HttpMethod.POST, "/hub/api/accounts",
            adminToken, "key-" + nextId(), createBody(accountName, "OWNER", String.valueOf(nextId()), null));
        assertThat(unknownOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(unknownOwner)).isEqualTo(9001);

        ResponseEntity<Map<String, Object>> hubWithOwner = call(HttpMethod.POST, "/hub/api/accounts",
            adminToken, "key-" + nextId(),
            createBody(accountName, "HUB", owner.ownerId(), String.valueOf(nextId()), null));
        assertThat(hubWithOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(hubWithOwner)).isEqualTo(9001);

        assertThat(jdbc.queryForObject("select count(*) from wms_account where account_name = ?",
            Integer.class, accountName)).as("被拒的请求不创建账号").isZero();
    }

    @Test
    @DisplayName("开通货主端账号：响应带端与货主编号、名称，详情与列表一致")
    void 响应带端与货主字段() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("回显货主-" + nextId(),
            uniqueCreditCode());
        String accountName = "owner-account-" + nextId();

        long accountId = createOwnerAccount(accountName, owner.ownerId());

        Map<String, Object> detail = data(call(HttpMethod.GET, "/hub/api/accounts/" + accountId,
            adminToken, null, null));
        assertThat(detail.get("client")).as("端标识随响应返回").isEqualTo("OWNER");
        assertThat(detail.get("ownerId")).isEqualTo(owner.ownerId());
        assertThat(detail.get("ownerNo")).isEqualTo(owner.ownerNo());
        assertThat((String) detail.get("ownerName")).as("名称来自货主档案，不是裸标识")
            .isNotBlank().doesNotContain(owner.ownerId());

        Map<String, Object> page = data(call(HttpMethod.GET, "/hub/api/accounts?keyword="
            + accountName, adminToken, null, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("client")).isEqualTo("OWNER");
        assertThat(items.get(0).get("ownerId")).isEqualTo(owner.ownerId());
        assertThat(items.get(0).get("ownerNo")).isEqualTo(owner.ownerNo());
        assertThat(items.get(0).get("ownerName")).isEqualTo(detail.get("ownerName"));
    }

    @Test
    @DisplayName("货主端账号可授 OWNER_USER：登录成功、拿到该角色的权限，货主端接口不再 AUTHZ_2001")
    void 货主端账号可授货主端角色() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("货主端角色货主-" + nextId(),
            uniqueCreditCode());
        String accountName = "owner-role-" + nextId();
        long accountId = createAccount(accountName, "OWNER", owner.ownerId(), null, PASSWORD,
            List.of(String.valueOf(roleId("OWNER_USER"))));

        // 一、角色真的落库了（不是受理单说成功、库里没有）
        assertThat(jdbc.queryForList("""
            select r.role_code from wms_account_role ar join wms_role r on r.id = ar.role_id
             where ar.account_id = ?
            """, String.class, accountId))
            .as("货主端账号授 OWNER_USER 必须落库；写死 HUB 端的旧实现会在这里 ROLE_4002 建不出账号")
            .containsExactly("OWNER_USER");

        // 二、登录：按账号所在端签发货主端令牌，角色与权限点取自 OWNER 端
        LoginAttempt login = login(accountName, PASSWORD);
        assertThat(login.status()).as("货主端账号必须能登录，响应体 %s", login.body())
            .isEqualTo(HttpStatus.OK.value());
        Map<String, Object> data = loginData(login);
        assertThat(texts(data.get("roles"))).contains("OWNER_USER");
        assertThat(texts(data.get("permissions")))
            .as("权限点随角色下发，AUTHZ_2001 的根因就是这里为空")
            .contains("archive.owner.read");
        assertThat(data.get("accountId")).isEqualTo(String.valueOf(accountId));

        // 三、拿这个令牌调货主端接口：不再是 AUTHZ_2001（缺权限点），而是正常业务响应
        String accessToken = "Bearer " + data.get("accessToken");
        Attempt list = send("GET", "/api/owner/archives", accessToken, null);
        assertThat(list.status()).as("持有 OWNER_USER 的货主端令牌必须能进货主端接口，实际 %s", list)
            .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("货主端账号挂运营端角色：入口 400 ROLE_4002，不创建账号")
    void 货主端账号不得挂运营端角色() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("端不匹配货主-" + nextId(),
            uniqueCreditCode());
        String accountName = "owner-with-hub-role-" + nextId();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/hub/api/accounts",
            adminToken, "key-" + nextId(),
            createBody(accountName, "OWNER", owner.ownerId(), null, PASSWORD,
                List.of(String.valueOf(roleId("MASTER_DATA_ADMIN")))));

        assertThat(response.getStatusCode()).as("角色端与账号端不符是参数错误，直接 400")
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(response)).isEqualTo(4002);
        assertThat(jdbc.queryForObject("select count(*) from wms_account where account_name = ?",
            Integer.class, accountName)).as("被拒的请求不创建账号").isZero();
    }

    @Test
    @DisplayName("运营端账号挂 OWNER_USER：入口 400 ROLE_4002，不创建账号")
    void 运营端账号不得挂货主端角色() {
        String accountName = "hub-with-owner-role-" + nextId();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/hub/api/accounts",
            adminToken, "key-" + nextId(),
            createBody(accountName, "HUB", null, String.valueOf(nextId()), PASSWORD,
                List.of(String.valueOf(roleId("OWNER_USER")))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(response)).isEqualTo(4002);
        assertThat(jdbc.queryForObject("select count(*) from wms_account where account_name = ?",
            Integer.class, accountName)).as("被拒的请求不创建账号").isZero();
    }

    @Test
    @DisplayName("API-009：角色端与账号端不符，入口 400 ROLE_4002 且不占受理号")
    void 替换角色入口校验端不匹配() {
        long accountId = createAccount("role-mismatch-" + nextId(), "HUB", null);
        String idempotencyKey = "roles-mismatch-" + nextId();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PUT,
            "/hub/api/accounts/" + accountId + "/roles", adminToken, idempotencyKey,
            """
            {"roleIds":["%s"],"version":0}
            """.formatted(roleId("OWNER_USER")));

        assertThat(response.getStatusCode())
            .as("与 API-006 同一口径：角色端与账号端不符属参数错误，入口直接 400")
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(response)).isEqualTo(4002);
        assertThat(jdbc.queryForObject(
            "select count(*) from wms_acceptance where idempotency_key = ?",
            Integer.class, idempotencyKey)).as("入口拒绝不占用受理号").isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from wms_account_role where account_id = ?",
            Integer.class, accountId)).as("被拒的请求不改动既有角色").isZero();
    }

    @Test
    @DisplayName("roleIds 重复：按集合语义去重，不误报「角色不存在」")
    void 角色重复标识不误报不存在() {
        long roleId = roleId("MASTER_DATA_ADMIN");
        List<String> duplicated = List.of(String.valueOf(roleId), String.valueOf(roleId));

        // API-006：同一个角色写两遍仍要受理成功，且只落一条绑定
        long accountId = createAccount("dup-role-" + nextId(), "HUB", null, String.valueOf(nextId()),
            PASSWORD, duplicated);
        assertThat(accountRoleCount(accountId, roleId)).as("重复标识只落一条绑定").isEqualTo(1);

        // API-009：整体替换同样是集合语义，重复标识既不是 ROLE_4001 也不是参数错误
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PUT,
            "/hub/api/accounts/" + accountId + "/roles", adminToken, "roles-dup-" + nextId(),
            """
            {"roleIds":["%s","%s"],"version":0}
            """.formatted(roleId, roleId));
        assertThat(response.getStatusCode())
            .as("重复标识不是参数错误，仍走受理，实际响应 %s", response.getBody())
            .isEqualTo(HttpStatus.ACCEPTED);
        awaitAcceptance((String) data(response).get("acceptNo"));
        assertThat(accountRoleCount(accountId, roleId))
            .as("整体替换后仍只有一条绑定").isEqualTo(1);
    }

    @Test
    @DisplayName("运营端账号：端为 HUB，三个货主字段为空")
    void 运营端账号无货主字段() {
        String accountName = "hub-account-" + nextId();
        long accountId = createAccount(accountName, "HUB", null);

        Map<String, Object> detail = data(call(HttpMethod.GET, "/hub/api/accounts/" + accountId,
            adminToken, null, null));

        assertThat(detail.get("client")).isEqualTo("HUB");
        assertThat(detail.get("ownerId")).isNull();
        assertThat(detail.get("ownerNo")).isNull();
        assertThat(detail.get("ownerName")).isNull();
    }

    @Test
    @DisplayName("已绑定货主的账号：携带不同 ownerId 的修改 400 拒绝")
    void 绑定不可改() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("绑定不可改货主-" + nextId(),
            uniqueCreditCode());
        OwnerCommandService.OwnerResultView another = prepareOwner("另一个货主-" + nextId(),
            uniqueCreditCode());
        long accountId = createOwnerAccount("immutable-" + nextId(), owner.ownerId());

        // 账号修改是 PATCH：TestRestTemplate 默认的请求工厂不支持 PATCH，这里用 JDK 的 HttpClient 发。
        Attempt response = send("PATCH", "/hub/api/accounts/" + accountId, adminToken, """
            {"ownerId":"%s","resetPassword":"NewOwner@12345","version":0}
            """.formatted(another.ownerId()));

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.code()).isEqualTo(9001);
        assertThat(jdbc.queryForObject("select owner_id from wms_account where id = ?",
            Long.class, accountId)).isEqualTo(Long.valueOf(owner.ownerId()));
    }

    @Test
    @DisplayName("库层兜底：绕过应用直接改 wms_account.owner_id 被触发器拒绝")
    void 库层拒绝改绑定() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("库层兜底货主-" + nextId(),
            uniqueCreditCode());
        long accountId = createOwnerAccount("db-immutable-" + nextId(), owner.ownerId());

        assertThatThrownBy(() -> jdbc.update("update wms_account set owner_id = ? where id = ?",
            nextId(), accountId))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("货主停用后：该账号登录被拒，已签发的货主端令牌下一次请求即失效")
    void 停用后登录与令牌失效() {
        OwnerCommandService.OwnerResultView owner = prepareOwner("停用登录货主-" + nextId(),
            uniqueCreditCode());
        String accountName = "login-account-" + nextId();
        long accountId = createOwnerAccount(accountName, owner.ownerId());

        // 停用前：令牌能过第一道门（业务侧的 403 由控制器给，不是 401）。
        String token = ownerToken(accountId);
        ResponseEntity<Map<String, Object>> usable = call(HttpMethod.GET, "/api/hub/owners", token,
            null, null);
        assertThat(usable.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ownerCommandService.disable(owner.ownerNo(), OwnerScope.all(), 0L, "合同到期",
            MIGRATION_OPERATOR);

        // 401 一律用 JDK 的 HttpClient 发：TestRestTemplate 底层的 HttpURLConnection 在
        // 「带请求体 + 服务端回 401」时会抛 cannot retry due to server authentication，取不到响应体。
        Attempt login = send("POST", "/hub/api/auth/login", null, """
            {"accountName":"%s","password":"%s"}
            """.formatted(accountName, PASSWORD));
        assertThat(login.status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(login.code()).as("按账号不可用处置，不新增错误码").isEqualTo(1003);

        Attempt rejected = send("GET", "/api/hub/owners", token, null);
        assertThat(rejected.status()).as("已签发令牌在下一请求被拒")
            .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(rejected.code()).isEqualTo(1003);
    }

    /** 造一个持 {@code PERMISSION_ADMIN} 的运营端账号：账号接口的权限点全集都在这个种子上。 */
    private long createPermissionAdminAccount() {
        long accountId = nextId();
        createdAccounts.add(accountId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into wms_account (id, account_name, password_hash, employee_id, owner_id, status,
                version, created_at, created_by, updated_at, updated_by)
            values (?, ?, ?, null, null, 'ENABLED', 0, ?, 0, ?, 0)
            """, accountId, "t-perm-admin-" + nextId(),
            "$2a$10$testonlytestonlytestonlytestonlytestonlytestonly", now, now);
        jdbc.update("insert into wms_account_role (account_id, role_id) "
            + "select ?, id from wms_role where role_code = 'PERMISSION_ADMIN'", accountId);
        return accountId;
    }

    private long createOwnerAccount(String accountName, String ownerId) {
        return createAccount(accountName, "OWNER", ownerId, null);
    }

    /** 走完「提交受理 → 轮询受理结果 → 回查账号」的完整链路，返回新账号标识。 */
    private long createAccount(String accountName, String client, String ownerId) {
        // 运营端账号必绑员工，货主端账号不绑员工：两者互斥，由 client 决定带哪个字段。
        String employeeId = "HUB".equals(client) ? String.valueOf(nextId()) : null;
        return createAccount(accountName, client, ownerId, employeeId);
    }

    private long createAccount(String accountName, String client, String ownerId, String employeeId) {
        return createAccount(accountName, client, ownerId, employeeId, PASSWORD, List.of());
    }

    /** 走完整链路的开通：请求体带 roleIds，受理成功后回查账号标识。 */
    private long createAccount(String accountName, String client, String ownerId, String employeeId,
                               String password, List<String> roleIds) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/hub/api/accounts",
            adminToken, "create-" + nextId(),
            createBody(accountName, client, ownerId, employeeId, password, roleIds));
        assertThat(response.getStatusCode())
            .as("写接口按契约返回 202 受理票据，实际 %s，响应体 %s", response.getStatusCode(),
                response.getBody())
            .isEqualTo(HttpStatus.ACCEPTED);
        String acceptNo = (String) data(response).get("acceptNo");
        awaitAcceptance(acceptNo);

        Long accountId = jdbc.queryForObject("select id from wms_account where account_name = ?",
            Long.class, accountName);
        assertThat(accountId).as("受理成功后账号应已落库").isNotNull();
        createdAccounts.add(accountId);
        return accountId;
    }

    private void awaitAcceptance(String acceptNo) {
        long deadline = System.nanoTime() + ACCEPTANCE_WAIT.toNanos();
        Map<String, Object> last = null;
        while (System.nanoTime() < deadline) {
            last = data(call(HttpMethod.GET, "/hub/api/acceptances/" + acceptNo, adminToken, null,
                null));
            if ("SUCCEEDED".equals(last.get("status"))) {
                return;
            }
            if ("FAILED".equals(last.get("status"))) {
                throw new AssertionError("受理失败：" + last);
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("受理未在 " + ACCEPTANCE_WAIT + " 内完成，最后响应 " + last);
    }

    private String createBody(String accountName, String client, String ownerId, String password) {
        return createBody(accountName, client, ownerId, null, password);
    }

    private String createBody(String accountName, String client, String ownerId, String employeeId,
                              String password) {
        return createBody(accountName, client, ownerId, employeeId, password, List.of());
    }

    private String createBody(String accountName, String client, String ownerId, String employeeId,
                              String password, List<String> roleIds) {
        // 只拼数组元素，方括号留在模板里：这里再带一层 [] 会变成 [["..."]]，被标识解析拒绝。
        String roleIdsJson = roleIds == null || roleIds.isEmpty()
            ? ""
            : roleIds.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return """
            {"accountName":"%s","employeeId":%s,"client":"%s","ownerId":%s,
             "initialPassword":"%s","roleIds":[%s]}
            """.formatted(accountName,
            employeeId == null ? "null" : "\"" + employeeId + "\"", client,
            ownerId == null ? "null" : "\"" + ownerId + "\"",
            password == null ? "Owner@12345" : password, roleIdsJson);
    }

    /** 某账号某角色的绑定条数：用于断言「整体替换是集合语义」。 */
    private int accountRoleCount(long accountId, long roleId) {
        return jdbc.queryForObject("""
            select count(*) from wms_account_role where account_id = ? and role_id = ?
            """, Integer.class, accountId, roleId);
    }

    /** 种子角色标识：按编码查表，不在测试里硬编码主键。 */
    private long roleId(String roleCode) {
        Long id = jdbc.queryForObject("select id from wms_role where role_code = ?", Long.class,
            roleCode);
        assertThat(id).as("种子角色 %s 必须由发布脚本建立", roleCode).isNotNull();
        return id;
    }

    /**
     * 走真登录口：既断言状态，也要读 {@code roles} 与 {@code permissions}——
     * 「货主端账号能不能拿到角色权限」只有登录响应能回答。
     */
    private LoginAttempt login(String accountName, String password) {
        String json = """
            {"accountName":"%s","password":"%s"}
            """.formatted(accountName, password);
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + "/hub/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode body =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            return new LoginAttempt(response.statusCode(), body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loginData(LoginAttempt attempt) {
        com.fasterxml.jackson.databind.JsonNode data = attempt.body().path("data");
        assertThat(data.isObject()).as("登录成功必须带 data，实际响应 %s", attempt.body()).isTrue();
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(data, Map.class);
    }

    private List<String> texts(Object values) {
        assertThat(values).isInstanceOf(List.class);
        return ((List<?>) values).stream().map(String::valueOf).toList();
    }

    private int code(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        return ((Number) response.getBody().get("code")).intValue();
    }

    /**
     * 直接发原始 HTTP 请求：PATCH（TestRestTemplate 的默认请求工厂不支持）与
     * 401 响应（HttpURLConnection 在带请求体时取不到响应体）都走这里。
     */
    private Attempt send(String method, String path, String token, String json) {
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", token == null ? "" : token)
                .header("Idempotency-Key", "raw-" + nextId())
                .method(method, json == null
                    ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode body =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            return new Attempt(response.statusCode(), body.path("code").asInt());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Attempt(int status, int code) {
    }

    /** 登录响应：状态码与完整响应体（{@code data} 里有 roles、permissions 与令牌）。 */
    private record LoginAttempt(int status, com.fasterxml.jackson.databind.JsonNode body) {
    }
}
