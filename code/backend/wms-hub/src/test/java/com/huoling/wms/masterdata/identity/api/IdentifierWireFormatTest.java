package com.huoling.wms.masterdata.identity.api;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.identity.application.AuditQueryService;
import com.huoling.wms.masterdata.identity.domain.DataScope;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 契约「标识、类型与枚举约定」的边界行为：出参十进制字符串、入参只收字符串、
 * 请求指纹不因类型从数字改字符串而漂移。
 */
class IdentifierWireFormatTest {

    /** 与 application.yml 的 spring.jackson.mapper.allow-coercion-of-scalars=false 等价。 */
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false);

    @Test
    @DisplayName("出参转换：超长主键逐位保留，可空标识仍是 null")
    void 出参转字符串() {
        // 雪花号超出 JS 安全整数上界 9007199254740991，字符串必须逐位一致。
        assertThat(Identifiers.toWire(2125394920742858753L)).isEqualTo("2125394920742858753");
        assertThat(Identifiers.toWire((Long) null)).isNull();
        assertThat(Identifiers.toWire(List.of(1001L, 1002L))).containsExactly("1001", "1002");
    }

    @Test
    @DisplayName("入参校验：非十进制字符串与超长数字按 COMMON_9001 拒绝")
    void 入参拒绝非法形态() {
        for (String raw : new String[]{"0", "007", "-1", "abc", "", "1.0", " 12", null}) {
            assertThatThrownBy(() -> Identifiers.parse(raw, "id"))
                .isInstanceOf(IdentityException.class)
                .extracting(e -> ((IdentityException) e).code())
                .isEqualTo(IdentityErrorCodes.COMMON_BAD_REQUEST);
        }
        // 形态合法但超出 int64：仍是入口参数错误，不是 5xx。
        assertThatThrownBy(() -> Identifiers.parse("99999999999999999999", "id"))
            .isInstanceOf(IdentityException.class)
            .extracting(e -> ((IdentityException) e).code())
            .isEqualTo(IdentityErrorCodes.COMMON_BAD_REQUEST);
    }

    @Test
    @DisplayName("请求体里的数字标识在反序列化阶段被拒绝")
    void 请求体拒绝数字标识() {
        assertThatThrownBy(() -> mapper.readValue("{\"roleIds\":[1001],\"version\":0}",
            AccountController.AssignRolesRequest.class))
            .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"accountName\":\"qa_user\",\"employeeId\":9102,"
            + "\"initialPassword\":\"Secret@123\"}", AccountController.CreateRequest.class))
            .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"scopes\":[{\"dimension\":\"OWNER\","
            + "\"scopeType\":\"LIST\",\"targetIds\":[1001]}],\"version\":0}",
            RoleController.ReplaceScopesRequest.class))
            .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("API-004 数据域：两个标识列表元素为字符串，空数组仍是空数组")
    void 数据域标识转字符串() {
        AuthController.DataScopeView all = AuthController.DataScopeView.from(DataScope.all());
        assertThat(all.ownerIds()).isEmpty();
        assertThat(all.warehouseIds()).isEmpty();
        assertThat(all.allOwners()).isTrue();
        assertThat(all.empty()).isFalse();

        AuthController.DataScopeView listed = AuthController.DataScopeView.from(
            new DataScope(false, new LinkedHashSet<>(List.of(2125394920742858753L, 1002L)),
                false, new LinkedHashSet<>(List.of(1001L))));
        // 雪花号逐位保留，顺序与库内 LinkedHashSet 一致。
        assertThat(listed.ownerIds()).containsExactly("2125394920742858753", "1002");
        assertThat(listed.warehouseIds()).containsExactly("1001");
        assertThat(listed.empty()).isFalse();

        // 指定列表且为空是「无可见数据」，不是全部。
        assertThat(AuthController.DataScopeView.from(
            new DataScope(false, Set.of(), true, Set.of())).empty()).isTrue();
    }

    @Test
    @DisplayName("API-017 条目：operatorId 转字符串，targetAccountId 可为 null")
    void 审计条目标识转字符串() {
        AuditController.AuditLogResponse response = AuditController.toResponse(
            new AuditQueryService.LogView(7L, 2125394920742858753L, null, "ACCOUNT_CREATE",
                null, null, null));
        assertThat(response.id()).isEqualTo("7");
        assertThat(response.operatorId()).isEqualTo("2125394920742858753");
        assertThat(response.targetAccountId()).isNull();

        // 账号类操作给出目标账号的字符串形态。
        assertThat(AuditController.toResponse(new AuditQueryService.LogView(8L, 7L,
            2125213027716042753L, "ACCOUNT_UPDATE", null, null, null)).targetAccountId())
            .isEqualTo("2125213027716042753");
        // 非账号类操作库内以 0 占位，线上必须是 null，不能落成不符合 pattern 的 "0"。
        assertThat(AuditController.toResponse(new AuditQueryService.LogView(9L, 7L, 0L,
            "REPLACE_SCOPES", null, null, null)).targetAccountId()).isNull();
    }

    @Test
    @DisplayName("字符串标识照常解析，指纹文本与改造前的数字形态一致")
    void 字符串标识解析且指纹稳定() throws Exception {
        AccountController.AssignRolesRequest assignRoles = mapper.readValue(
            "{\"roleIds\":[\"1001\",\"1002\"],\"version\":3}",
            AccountController.AssignRolesRequest.class);
        assertThat(Identifiers.parseAll(assignRoles.roleIds(), "roleIds"))
            .containsExactly(1001L, 1002L);
        // 受理单指纹取请求体 record 的 toString：字符串与旧数字形态渲染相同，历史受理单仍能命中。
        assertThat(assignRoles.toString())
            .isEqualTo("AssignRolesRequest[roleIds=[1001, 1002], version=3]");

        AccountController.CreateRequest create = mapper.readValue(
            "{\"accountName\":\"qa_user\",\"employeeId\":\"9102\","
                + "\"initialPassword\":\"Secret@123\",\"roleIds\":[\"1001\"]}",
            AccountController.CreateRequest.class);
        assertThat(Identifiers.parse(create.employeeId(), "employeeId")).isEqualTo(9102L);
        assertThat(create.toString())
            .isEqualTo("CreateRequest[accountName=qa_user, employeeId=9102, "
                + "initialPassword=Secret@123, roleIds=[1001]]");
    }
}
