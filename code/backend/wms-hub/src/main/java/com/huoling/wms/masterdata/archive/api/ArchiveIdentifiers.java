package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;

import java.util.regex.Pattern;

/**
 * 档案接口的标识边界转换：库内 {@code bigint}，线上十进制字符串。
 *
 * <p>与身份模块的 {@code Identifiers} 同一口径（无前导零、非零开头），
 * 数字形态与非法形态一律按 {@code PARAM_INVALID} 拒绝，不做隐式转换。
 */
final class ArchiveIdentifiers {

    private static final Pattern DECIMAL = Pattern.compile("^[1-9][0-9]*$");

    private ArchiveIdentifiers() {
    }

    static String toWire(Long value) {
        return value == null ? null : value.toString();
    }

    /** 入参标识转库内值；{@code null} 返回 {@code null}，由业务层判定必填。 */
    static Long parseOrNull(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!DECIMAL.matcher(raw).matches()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "字段 " + field + " 必须为十进制字符串形态，收到 " + raw);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "字段 " + field + " 超出标识取值范围");
        }
    }
}
