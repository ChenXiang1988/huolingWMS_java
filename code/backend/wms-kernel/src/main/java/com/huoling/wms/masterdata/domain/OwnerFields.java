package com.huoling.wms.masterdata.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 货主档案的可填字段（契约 1.5、API-023/024 请求体）：名称、联系人、联系电话、信用代码四项。
 *
 * <p>字段校验只有这里一套：长度、字符集、四项必填都由 {@link #requireComplete()} 判定，
 * Hub 的建档与改档、Site 的副本校验都调用它，不各写一套（OI-03）。
 *
 * <p>三个口径写死在这里，避免各层各自解释：
 * <ul>
 *   <li>{@code null}、空串、全空格一律按缺失处理；</li>
 *   <li>名称允许重复，本类不做任何去重（OI-05）；</li>
 *   <li>电话不做号段校验，只做长度与字符集（契约 1.5）。</li>
 * </ul>
 *
 * <p>为什么另有 {@link #issues()}：契约 1.1 要求字段级错误在 {@code details[].field} 里
 * 指出具体字段，而异常消息里只有一句人话。{@code issues()} 给出结构化的字段名与说明，
 * 入口层据此拼 {@code details}；两项判定仍是同一份规则，不存在第二套校验。
 */
public record OwnerFields(
        String ownerName,
        String contactName,
        String contactPhone,
        String creditCode) {

    /** 名称上限（契约 1.5：1 至 128 字）。 */
    public static final int OWNER_NAME_MAX = 128;
    /** 联系人上限（契约 1.5：1 至 64 字）。 */
    public static final int CONTACT_NAME_MAX = 64;
    /** 电话长度下限（契约 1.5：6 至 32 位）。 */
    public static final int CONTACT_PHONE_MIN = 6;
    /** 电话长度上限。 */
    public static final int CONTACT_PHONE_MAX = 32;
    /** 停用与启用原因上限（契约 1.5：1 至 200 字）。 */
    public static final int REASON_MAX = 200;

    /** 与数据库 {@code ck_wms_owner_contact_phone} 同一口径：数字与 + - ( ) 与空格。 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+() -]{6,32}$");

    /**
     * 四项必填与形态校验：缺一即拒绝，`details[].field` 由接口层按异常里的字段名给出。
     *
     * @throws OwnerRuleViolation 任一字段缺失、超长或字符集不符时为 {@code PARAM_INVALID}
     */
    public void requireComplete() {
        List<Issue> issues = issues();
        if (!issues.isEmpty()) {
            // 只抛第一条：与逐项校验的顺序一致（名称、联系人、电话、信用代码）。
            throw invalid(issues.get(0).reason());
        }
    }

    /**
     * 四项逐字段的校验结果：字段名与说明，齐备时为空。
     *
     * <p>顺序固定为名称、联系人、电话、信用代码；{@link #requireComplete()} 与接口层的
     * {@code details[].field} 都用这一份，不各算一遍。
     */
    public List<Issue> issues() {
        List<Issue> issues = new ArrayList<>();
        textIssue(ownerName, "ownerName", OWNER_NAME_MAX).ifPresent(issues::add);
        textIssue(contactName, "contactName", CONTACT_NAME_MAX).ifPresent(issues::add);
        phoneIssue(contactPhone).ifPresent(issues::add);
        creditCodeIssue(creditCode).ifPresent(issues::add);
        return List.copyOf(issues);
    }

    /**
     * 停用与启用原因：必填、1 至 200 字（契约 1.5、API-025/026 请求体）。
     *
     * <p>放在这里而不是各应用服务里：原因与四项同属字段级规则，必须只有一处口径。
     */
    public static void requireReason(String reason) {
        reasonIssue(reason).ifPresent(issue -> {
            throw invalid(issue.reason());
        });
    }

    /** 原因字段的校验结果；合法时为空。 */
    public static Optional<Issue> reasonIssue(String reason) {
        if (reason == null || reason.isBlank()) {
            return Optional.of(new Issue("reason", "字段 reason 必填"));
        }
        if (reason.length() > REASON_MAX) {
            return Optional.of(new Issue("reason", "字段 reason 长度上限 " + REASON_MAX));
        }
        return Optional.empty();
    }

    /** 单字段的校验结果：契约字段名与给人看的说明。 */
    public record Issue(String field, String reason) {
    }

    private static Optional<Issue> textIssue(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return Optional.of(new Issue(field, "字段 " + field + " 必填"));
        }
        if (value.length() > maxLength) {
            return Optional.of(new Issue(field, "字段 " + field + " 长度上限 " + maxLength));
        }
        return Optional.empty();
    }

    private static Optional<Issue> phoneIssue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(new Issue("contactPhone", "字段 contactPhone 必填"));
        }
        if (value.length() < CONTACT_PHONE_MIN || value.length() > CONTACT_PHONE_MAX) {
            return Optional.of(new Issue("contactPhone", "字段 contactPhone 长度必须为 "
                + CONTACT_PHONE_MIN + " 到 " + CONTACT_PHONE_MAX + " 位"));
        }
        if (!PHONE_PATTERN.matcher(value).matches()) {
            return Optional.of(new Issue("contactPhone", "字段 contactPhone 只允许数字与 + - ( ) 与空格"));
        }
        return Optional.empty();
    }

    private static Optional<Issue> creditCodeIssue(String value) {
        return OwnerCreditCodeRule.reasonOf(value)
            .map(reason -> new Issue("creditCode", reason));
    }

    private static OwnerRuleViolation invalid(String message) {
        return new OwnerRuleViolation(OwnerRuleCodes.PARAM_INVALID, message);
    }
}
