package com.huoling.wms.masterdata.owner.support;

import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import com.huoling.wms.masterdata.owner.application.OwnerCommandService;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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

/**
 * 货主档案接口的集成测试支撑：真 HTTP + 真库 + 真令牌。
 *
 * <p>在 {@link ArchiveDbTestSupport} 的造数与自清基础上补三件事：
 * <ul>
 *   <li>HTTP 调用与受理轮询：走过滤器、权限拦截器、异常映射与 JSON 序列化的完整链路，
 *       不 mock 任何一层；</li>
 *   <li>货主档案行与货主域账号的自清：{@code wms_owner} 由本类登记后按 id 删除，
 *       域账号与自造角色按创建顺序反向删除；</li>
 *   <li>主数据管理员之外的两类账号：指定货主域（列表）与空域（列表为空），
 *       用于覆盖「域为指定列表」与「域为空」两种判定。</li>
 * </ul>
 *
 * <p>货主域账号自造角色而不改种子角色：往种子角色上挂 LIST 域会污染同一次运行里的其他用例，
 * 而且种子角色的绑定由发布脚本负责，测试不该改它。
 */
public abstract class OwnerApiTestSupport extends ArchiveDbTestSupport {

    /** 轮询受理结果的等待上限：远大于受理线程池的实际耗时，只防挂死。 */
    protected static final Duration ACCEPTANCE_WAIT = Duration.ofSeconds(20);

    /** 迁移账号：脚本与测试都用 0 表示非人操作。 */
    protected static final long MIGRATION_OPERATOR = 0L;

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JwtTokenService tokenService;

    @Autowired
    protected OwnerCommandService ownerCommandService;

    private final Set<Long> ownerRows = new LinkedHashSet<>();
    private final Set<Long> scopedAccounts = new LinkedHashSet<>();
    private final Set<Long> scopedRoles = new LinkedHashSet<>();
    private final Set<String> manualAcceptNos = new LinkedHashSet<>();

    /**
     * 先清受理记录、下行消息与自造角色，再交给父类删账号与受理记录。
     *
     * <p><b>货主档案行刻意不删</b>：{@code wms_owner} 上有 {@code trg_wms_owner_no_delete}
     * （{@code V20261143}），任何行级删除都会被库层拒绝，属主也不例外——这正是 INV-18 要的效果，
     * 测试清理不能靠删表绕过它（那等于把「库层真的挡住了删除」这条事实抹掉）。
     * 残留行不会污染后续运行：造数主键取自 {@code ArchiveDbTestSupport.nextId()}（每次 JVM 启动
     * 换一个基址），信用代码与编号随之唯一；列表与选择器用例都按本用例独有的名称前缀筛选。
     *
     * <p>顺序按外键方向来：先清受理记录与下行消息，再删角色与账号。
     */
    @AfterEach
    void cleanOwnerRows() {
        for (String acceptNo : manualAcceptNos) {
            jdbc.update("delete from hub_archive_acceptance where accept_no = ?", acceptNo);
        }
        for (Long ownerId : ownerRows) {
            // 下行消息在 hub_outbox 里没有外键，但留着会让下一次运行的投递器反复重试同一批行。
            jdbc.update("delete from hub_outbox where owner_id = ?", ownerId);
        }
        for (Long roleId : scopedRoles) {
            jdbc.update("delete from wms_role_scope_item where role_scope_id in "
                + "(select id from wms_role_scope where role_id = ?)", roleId);
            jdbc.update("delete from wms_role_scope where role_id = ?", roleId);
            jdbc.update("delete from wms_account_role where role_id = ?", roleId);
            jdbc.update("delete from wms_role_permission where role_id = ?", roleId);
            jdbc.update("delete from wms_role where id = ?", roleId);
        }
        for (Long accountId : scopedAccounts) {
            jdbc.update("delete from wms_account_role where account_id = ?", accountId);
            jdbc.update("delete from wms_account where id = ?", accountId);
        }
        ownerRows.clear();
        scopedAccounts.clear();
        scopedRoles.clear();
        manualAcceptNos.clear();
    }

    /** 登记一条本用例造出来的货主档案行，用例结束按 id 删除。 */
    protected void registerOwnerRow(long ownerId) {
        ownerRows.add(ownerId);
    }

    /** 直接建档（不经过受理）：只用于给查询类用例备数据。 */
    protected OwnerCommandService.OwnerResultView prepareOwner(String ownerName, String creditCode) {
        OwnerCommandService.OwnerResultView created = ownerCommandService.create(OwnerScope.all(),
            new OwnerFields(ownerName, "张三", "021-62345678", creditCode), MIGRATION_OPERATOR);
        ownerRows.add(Long.valueOf(created.ownerId()));
        return created;
    }

    /** 建档并把状态推到停用（用于选择器与详情用例的「停用档」）。 */
    protected OwnerCommandService.OwnerResultView prepareDisabledOwner(String ownerName) {
        OwnerCommandService.OwnerResultView created = prepareOwner(ownerName, uniqueCreditCode());
        ownerCommandService.disable(created.ownerNo(), OwnerScope.all(), created.version(),
            "用例前置：准备停用档", MIGRATION_OPERATOR);
        return created;
    }

    /** 四项齐备的请求体。 */
    protected static OwnerFields fields(String ownerName) {
        return new OwnerFields(ownerName, "张三", "021-62345678", uniqueCreditCode());
    }

    /**
     * 18 位信用代码：前两位固定 91，其余取本进程造数标识。
     *
     * <p>进制 36 的补位里可能出现 I／O／S／V／Z 之外的字符——这里只用数字，
     * 保证落在 {@code ^[0-9A-HJ-NP-RT-UW-Y]{18}$} 内且同一次运行内不撞唯一索引。
     */
    protected static String uniqueCreditCode() {
        return "91" + String.format("%016d", Math.floorMod(nextId(), 10_000_000_000_000_000L));
    }

    /** 运营端令牌。 */
    protected String hubToken(long accountId) {
        return "Bearer " + tokenService.issue(accountId, ClientType.HUB);
    }

    /** 货主端令牌。 */
    protected String ownerToken(long accountId) {
        return "Bearer " + tokenService.issue(accountId, ClientType.OWNER);
    }

    /**
     * 造一个指定货主域（列表）的运营端账号：自造 HUB 角色，挂两项权限点，域为给定列表。
     *
     * @param ownerIds 域内货主；空集合表示「列表为空」，用于覆盖空域拒绝
     */
    protected long createScopedHubAccount(List<Long> ownerIds) {
        long accountId = insertScopedAccount("t-owner-scope-" + nextId());
        long roleId = insertHubRole("T_OWNER_SCOPE_" + nextId());
        jdbc.update("insert into wms_account_role (account_id, role_id) values (?, ?)",
            accountId, roleId);
        bindOwnerPermissions(roleId);
        long scopeId = nextId();
        jdbc.update("insert into wms_role_scope (id, role_id, dimension, scope_type) "
            + "values (?, ?, 'OWNER', 'LIST')", scopeId, roleId);
        for (Long ownerId : ownerIds) {
            jdbc.update("insert into wms_role_scope_item (id, role_scope_id, target_id) "
                + "values (?, ?, ?)", nextId(), scopeId, ownerId);
        }
        return accountId;
    }

    /**
     * 造一个只持 {@code owner.hub.read} 的运营端账号（全域）。
     *
     * <p>用来证明 API-020 的权限点确实换成了 {@code owner.hub.read}：这类账号没有代建点
     * {@code archive.hub.create}，改造前会被拒，改造后必须能查。
     */
    protected long createOwnerReadOnlyHubAccount() {
        long accountId = insertScopedAccount("t-owner-read-" + nextId());
        long roleId = insertHubRole("T_OWNER_READ_" + nextId());
        jdbc.update("insert into wms_account_role (account_id, role_id) values (?, ?)",
            accountId, roleId);
        bindPermissions(roleId, List.of("owner.hub.read"));
        return accountId;
    }

    /** 造一个没有货主档案权限点的运营端账号：用于覆盖 AUTHZ_2001。 */
    protected long createHubAccountWithoutOwnerPermissions() {
        long accountId = insertScopedAccount("t-hub-noperm-" + nextId());
        long roleId = insertHubRole("T_HUB_NOPERM_" + nextId());
        jdbc.update("insert into wms_account_role (account_id, role_id) values (?, ?)",
            accountId, roleId);
        return accountId;
    }

    /** 手工插一条目标货主的受理单：用于覆盖「处理中超过轮询上限」的兜底。 */
    protected String insertProcessingAcceptance(String apiCode, Long subjectOwnerId,
                                                Duration age) {
        String acceptNo = "ARC" + System.nanoTime();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).minus(age);
        jdbc.update("""
            insert into hub_archive_acceptance (id, accept_no, api_code, scope_key, account_id,
                idempotency_key, status_snapshot, accept_status, result_json, error_code,
                error_message, subject_owner_id, created_at, updated_at)
            values (?, ?, ?, 'HUB', 0, ?, 'ACTIVE', 'PROCESSING', null, null, null, ?, ?, ?)
            """, nextId(), acceptNo, apiCode, "manual-" + acceptNo, subjectOwnerId,
            createdAt, createdAt);
        manualAcceptNos.add(acceptNo);
        return acceptNo;
    }

    /** 通用 HTTP 调用：拼令牌与幂等键，响应按 Map 解析。 */
    protected ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token,
                                                       String idempotencyKey, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<String> entity = new HttpEntity<>(json, headers);
        return restTemplate.exchange("http://localhost:" + port + path, method, entity,
            new ParameterizedTypeReference<Map<String, Object>>() {
            });
    }

    /** 断言 2xx 并取 {@code data}。 */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
            .as("期望 2xx，实际 %s，响应体 %s", response.getStatusCode(), response.getBody()).isTrue();
        Map<String, Object> payload = response.getBody();
        assertThat(payload).isNotNull();
        return (Map<String, Object>) payload.get("data");
    }

    /** 按状态轮询受理查询：按状态等，不用固定 sleep 赌异步完成时间。 */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> awaitAcceptance(String acceptNo, String token, String expected) {
        long deadline = System.nanoTime() + ACCEPTANCE_WAIT.toNanos();
        Map<String, Object> last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
                "/api/hub/owner-archives/acceptances/" + acceptNo, token, null, null);
            assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
            last = data(response);
            if (expected.equals(last.get("acceptStatus"))) {
                return last;
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("受理查询未在 " + ACCEPTANCE_WAIT + " 内进入 " + expected
            + "，最后响应 " + last);
    }

    /** 从受理成功结果里取 {@code result} 摘要。 */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> resultOf(Map<String, Object> acceptance) {
        Object result = acceptance.get("result");
        assertThat(result).as("成功受理必须带结果摘要").isNotNull();
        return (Map<String, Object>) result;
    }

    private long insertScopedAccount(String accountName) {
        long accountId = nextId();
        scopedAccounts.add(accountId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into wms_account (id, account_name, password_hash, employee_id, owner_id, status,
                version, created_at, created_by, updated_at, updated_by)
            values (?, ?, ?, null, null, 'ENABLED', 0, ?, 0, ?, 0)
            """, accountId, accountName,
            "$2a$10$testonlytestonlytestonlytestonlytestonlytestonly", now, now);
        return accountId;
    }

    private long insertHubRole(String roleCode) {
        long roleId = nextId();
        scopedRoles.add(roleId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into wms_role (id, role_code, role_name, applies_to_client, status, version,
                created_at, created_by, updated_at, updated_by)
            values (?, ?, ?, 'HUB', 'ENABLED', 0, ?, 0, ?, 0)
            """, roleId, roleCode, roleCode, now, now);
        return roleId;
    }

    /** 把货主档案的两项权限点绑到自造角色：取值来自初始化脚本，不在这里自造权限点。 */
    private void bindOwnerPermissions(long roleId) {
        bindPermissions(roleId, List.of("owner.hub.read", "owner.hub.write"));
    }

    private void bindPermissions(long roleId, List<String> permCodes) {
        for (String code : permCodes) {
            jdbc.update("insert into wms_role_permission (role_id, permission_id) "
                + "select ?, id from wms_permission where perm_code = ?", roleId, code);
        }
    }
}
