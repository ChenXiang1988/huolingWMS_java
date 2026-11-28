package com.huoling.wms.masterdata.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 统一社会信用代码规则（契约 1.5、1.8「信用代码校验深度」）。
 *
 * <p>只校验两件事：固定 18 位；字符集为大写字母加数字，且字母排除 {@code I}、{@code O}、{@code S}、
 * {@code V}、{@code Z}（GB 32100 的排除字符集）。
 *
 * <p><b>不校验校验位</b>：校验位算法未在 `00`、`00a`、`21` 中登记，自行加严会把存量合法主体误拒；
 * <b>不静默转大小写</b>：小写提交直接按参数错误拒绝，否则「同一主体的两种写法」会绕过全局唯一性。
 */
public final class OwnerCreditCodeRule {

    /** 固定长度。 */
    public static final int LENGTH = 18;

    /** 与数据库 {@code ck_wms_owner_credit_code} 同一口径，两层判定值必须一致。 */
    private static final Pattern PATTERN = Pattern.compile("^[0-9A-HJ-NP-RT-UW-Y]{" + LENGTH + "}$");

    private OwnerCreditCodeRule() {
    }

    /**
     * 校验信用代码，不合法即拒绝。
     *
     * @throws OwnerRuleViolation 缺失为空、长度不是 18 位、含小写或排除字符时为 {@code PARAM_INVALID}
     */
    public static void requireValid(String creditCode) {
        Optional<String> reason = reasonOf(creditCode);
        if (reason.isPresent()) {
            throw new OwnerRuleViolation(OwnerRuleCodes.PARAM_INVALID, reason.get());
        }
    }

    /**
     * 不合法的原因；合法时为空。
     *
     * <p>{@link #requireValid(String)} 与 {@code OwnerFields.issues()} 共用这一份判定：
     * 两处各写一套长度与字符集，迟早会出现「入口放过、落库被 CHECK 拦住」的裂缝。
     */
    public static Optional<String> reasonOf(String creditCode) {
        if (creditCode == null || creditCode.isBlank()) {
            return Optional.of("字段 creditCode 必填");
        }
        if (creditCode.length() != LENGTH) {
            return Optional.of("字段 creditCode 必须为 " + LENGTH + " 位");
        }
        if (!PATTERN.matcher(creditCode).matches()) {
            return Optional.of("字段 creditCode 只允许大写字母（不含 I、O、S、V、Z）与数字");
        }
        return Optional.empty();
    }

    /** 是否合法；供查询参数与导入校验复用，不做异常分支。 */
    public static boolean valid(String creditCode) {
        return creditCode != null && PATTERN.matcher(creditCode).matches();
    }
}
