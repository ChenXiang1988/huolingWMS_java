package com.huoling.wms.masterdata.domain;

import java.util.regex.Pattern;

/**
 * 货主编号规则（契约 1.5、1.11「编号规则」，OI-01）。
 *
 * <p>对调用方的承诺只有四条：以 {@code OW} 开头、8 至 32 位、字符集 {@code [A-Z0-9]}、全局唯一且不可变。
 * 调用方 MUST NOT 解析或依赖固定位数——本类生成的形态是 {@code OW} 加 6 位十进制序号（首个
 * {@code OW000001}），序号超过六位时自然变长，这不构成破坏性变更。
 *
 * <p>编号只由系统生成：建档入口不接受 {@code ownerNo}（传入即未知字段），本类也没有「解析后改写」的方法。
 */
public final class OwnerNumberRule {

    /** 编号前缀。 */
    public static final String PREFIX = "OW";
    /** 最短长度：前缀两位加 6 位序号。 */
    public static final int MIN_LENGTH = 8;
    /** 最长长度：契约上限。 */
    public static final int MAX_LENGTH = 32;
    /** 首个编号的序号。 */
    public static final long FIRST_SEQUENCE = 1L;

    /** 与数据库 {@code ck_wms_owner_no_format} 同一口径。 */
    private static final Pattern PATTERN = Pattern.compile("^OW[A-Z0-9]{6,30}$");

    /** 序号补零到六位；{@code %06d} 只补零不截断，超过六位自然变长。 */
    private static final String SEQUENCE_FORMAT = "OW%06d";

    private OwnerNumberRule() {
    }

    /**
     * 由序号生成编号。
     *
     * @throws OwnerRuleViolation 序号小于 1 时为 {@code PARAM_INVALID}
     */
    public static String format(long sequence) {
        if (sequence < FIRST_SEQUENCE) {
            throw new OwnerRuleViolation(OwnerRuleCodes.PARAM_INVALID, "货主编号序号必须从 1 开始");
        }
        String ownerNo = String.format(SEQUENCE_FORMAT, sequence);
        requireValid(ownerNo);
        return ownerNo;
    }

    /**
     * 校验编号形态。
     *
     * @throws OwnerRuleViolation 缺失、长度越界、前缀不是 {@code OW}、含小写或特殊字符时为 {@code PARAM_INVALID}
     */
    public static void requireValid(String ownerNo) {
        if (ownerNo == null || ownerNo.isBlank()) {
            throw invalid("字段 ownerNo 必填");
        }
        if (ownerNo.length() < MIN_LENGTH || ownerNo.length() > MAX_LENGTH) {
            throw invalid("字段 ownerNo 长度必须为 " + MIN_LENGTH + " 到 " + MAX_LENGTH
                + " 位，以 " + PREFIX + " 开头");
        }
        if (!ownerNo.startsWith(PREFIX)) {
            throw invalid("字段 ownerNo 必须以 " + PREFIX + " 开头");
        }
        if (!PATTERN.matcher(ownerNo).matches()) {
            throw invalid("字段 ownerNo 只允许大写字母与数字，且以 " + PREFIX + " 开头");
        }
    }

    /** 是否合法；供筛选参数与路径参数校验复用，不做异常分支。 */
    public static boolean valid(String ownerNo) {
        return ownerNo != null && PATTERN.matcher(ownerNo).matches();
    }

    private static OwnerRuleViolation invalid(String message) {
        return new OwnerRuleViolation(OwnerRuleCodes.PARAM_INVALID, message);
    }
}
