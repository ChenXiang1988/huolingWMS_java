package com.huoling.wms.masterdata.identity.api;

import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 标识的 HTTP 边界转换：库内 {@code bigint}，线上十进制字符串（契约「标识、类型与枚举约定」）。
 *
 * <p>主键是雪花号，库内实际值如 {@code 2125394920742858753}，超出 JavaScript 安全整数上界
 * {@code 9007199254740991}，按 JSON 数字传输会在浏览器侧静默丢精度、回传后变成不存在的标识。
 * 因此出参一律 {@link #toWire(Long)} 转字符串，入参一律 {@link #parse(String, String)} 校验后
 * 再交给应用层，两端都不做隐式转换：数字形态、前导零、负号、空值与超长数字都在这里拒绝为
 * {@code COMMON_9001}，不进入受理环、不出 5xx。
 */
final class Identifiers {

    /** 契约给定的字符串形态：十进制、无前导零、非零开头。 */
    static final String PATTERN = "^[1-9][0-9]*$";

    private static final Pattern DECIMAL = Pattern.compile(PATTERN);

    private Identifiers() {
    }

    /** 出参：库内标识转线上字符串；{@code null} 原样返回，保持可空字段的语义。 */
    static String toWire(Long value) {
        return value == null ? null : value.toString();
    }

    /**
     * 出参：标识集合转线上字符串列表，保持契约里的顺序。
     * 收 {@link Collection} 是为了数据域的两个维度（库内为 {@code Set<Long>}）共用同一处转换；
     * 空集合仍转成空数组，不转 {@code null}。
     */
    static List<String> toWire(Collection<Long> values) {
        return values == null ? null : values.stream().map(Identifiers::toWire).toList();
    }

    /** 入参：线上字符串转库内标识，形态不符按 {@code COMMON_9001} 拒绝。 */
    static long parse(String raw, String field) {
        if (raw == null || !DECIMAL.matcher(raw).matches()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST,
                "字段 " + field + " 必须为十进制字符串形态（" + PATTERN + "），收到 " + quote(raw));
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // 位数超出 int64：字符串形态合法但库内装不下，同样是入口参数错误。
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST,
                "字段 " + field + " 超出标识取值范围（int64），收到 " + quote(raw));
        }
    }

    /** 入参：标识列表逐个校验；{@code null} 表示未给出，与空数组同义，交由业务层判定。 */
    static List<Long> parseAll(List<String> raw, String field) {
        if (raw == null) {
            return null;
        }
        List<Long> values = new ArrayList<>(raw.size());
        for (String item : raw) {
            values.add(parse(item, field));
        }
        return List.copyOf(values);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
