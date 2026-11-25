package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuListSortKey;
import com.huoling.wms.masterdata.domain.SkuSortDirection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 键集分页游标：以（排序键值, 主键）为锚点，不用 offset（11「分页不用 offset」）。
 *
 * <p>游标对调用方是不透明字符串，Base64URL 里依次放：排序字段、方向、主键与排序键值。
 * 排序字段与方向随游标一起编码，是为了让一个游标只对产生它的那种排序有效：
 * 换了排序键还拿旧锚点续翻，就会按另一种顺序做「大于/小于」比较，必然重复或漏掉记录，
 * 因此解码时按键与方向校验，不一致按参数错误拒绝，不回落成第一页。
 *
 * <p>时间值按 ISO-8601 原样编码，不截成毫秒：库里 {@code timestamptz} 带微秒，
 * 截断后同一行会再次落进下一页（翻页重复）。
 */
public record SkuPageCursor(SkuListSortKey key, SkuSortDirection direction, long id, String value) {

    private static final String SEPARATOR = "|";
    private static final int PARTS = 4;

    public SkuPageCursor {
        if (key == null) {
            throw invalid();
        }
        if (direction == null) {
            throw invalid();
        }
        if (value == null) {
            throw invalid();
        }
    }

    /** 时间排序的锚点（档案更新时间、申请创建时间）。 */
    public static SkuPageCursor ofTime(SkuListSort sort, Instant time, long id) {
        if (time == null) {
            throw invalid();
        }
        return new SkuPageCursor(sort.key(), sort.direction(), id, time.toString());
    }

    /** 文本排序的锚点（货主编码、名称、变更字段）。 */
    public static SkuPageCursor ofText(SkuListSort sort, String text, long id) {
        return new SkuPageCursor(sort.key(), sort.direction(), id, text == null ? "" : text);
    }

    /** 数值排序的锚点（变更申请按归属货主排序时用）。 */
    public static SkuPageCursor ofNumber(SkuListSort sort, long number, long id) {
        return new SkuPageCursor(sort.key(), sort.direction(), id, Long.toString(number));
    }

    /** 编码为不透明游标。 */
    public String encode() {
        String plain = key.name() + SEPARATOR + direction.name() + SEPARATOR + id + SEPARATOR + value;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码；空值表示第一页。
     *
     * @param raw  调用方回传的游标原文
     * @param sort 本次请求的排序参数；游标与它不一致即拒绝
     * @throws ArchiveException 形态不合法或与排序参数不一致时为 {@code PARAM_INVALID}
     */
    public static SkuPageCursor decode(String raw, SkuListSort sort) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String plain;
        try {
            plain = new String(Base64.getUrlDecoder().decode(raw.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        String[] parts = plain.split("\\" + SEPARATOR, PARTS);
        if (parts.length != PARTS) {
            throw invalid();
        }
        SkuListSortKey key;
        SkuSortDirection direction;
        long id;
        try {
            key = SkuListSortKey.valueOf(parts[0]);
            direction = SkuSortDirection.valueOf(parts[1]);
            id = Long.parseLong(parts[2]);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (sort == null || key != sort.key() || direction != sort.direction()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 cursor 与排序参数不一致，请回到第一页重新翻页");
        }
        return new SkuPageCursor(key, direction, id, parts[3]);
    }

    /** 时间型排序键值。 */
    public Instant instantValue() {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    /** 数值型排序键值。 */
    public long longValue() {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    /** 文本型排序键值。 */
    public String textValue() {
        return value;
    }

    private static ArchiveException invalid() {
        return new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "参数 cursor 形态不合法");
    }
}
