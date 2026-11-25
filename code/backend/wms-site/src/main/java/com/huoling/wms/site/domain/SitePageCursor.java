package com.huoling.wms.site.domain;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuListSortKey;
import com.huoling.wms.masterdata.domain.SkuSortDirection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 单仓端键集分页游标：以（排序键值, 主键）为锚点，不用 offset（契约「分页约定」：列表只用键集分页）。
 *
 * <p>与 Hub 侧的游标同形但各自实现：两个单元不共享应用层与基础设施层，只共享 kernel 与 common；
 * 对外的编码形态一致（Base64URL 里放排序字段、方向、主键与排序键值），便于三端统一理解，
 * 但游标只在产生它的那种排序下有效——换了排序键还拿旧锚点续翻会按另一种顺序比较，必然重复或漏记，
 * 因此解码时校验字段与方向，不一致按参数错误拒绝，不回落成第一页。
 *
 * <p>时间值按 ISO-8601 原样编码，不截成毫秒：库里 {@code timestamptz} 带微秒，截断会让同一行再出现一次。
 */
public record SitePageCursor(SkuListSortKey key, SkuSortDirection direction, long id, String value) {

    private static final String SEPARATOR = "|";
    private static final int PARTS = 4;

    public SitePageCursor {
        if (key == null || direction == null || value == null) {
            throw invalid();
        }
    }

    /** 时间排序的锚点（档案数据时间戳、申请创建时间）。 */
    public static SitePageCursor ofTime(SkuListSort sort, Instant time, long id) {
        if (time == null) {
            throw invalid();
        }
        return new SitePageCursor(sort.key(), sort.direction(), id, time.toString());
    }

    /** 文本排序的锚点（货主编码、名称、变更字段）。 */
    public static SitePageCursor ofText(SkuListSort sort, String text, long id) {
        return new SitePageCursor(sort.key(), sort.direction(), id, text == null ? "" : text);
    }

    /** 数值排序的锚点（申请按归属货主排序时用）。 */
    public static SitePageCursor ofNumber(SkuListSort sort, long number, long id) {
        return new SitePageCursor(sort.key(), sort.direction(), id, Long.toString(number));
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
     * @throws SiteException 形态不合法或与排序参数不一致时为 {@code PARAM_INVALID}
     */
    public static SitePageCursor decode(String raw, SkuListSort sort) {
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
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "参数 cursor 与排序参数不一致，请回到第一页重新翻页");
        }
        return new SitePageCursor(key, direction, id, parts[3]);
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

    private static SiteException invalid() {
        return new SiteException(ArchiveContractCodes.PARAM_INVALID, "参数 cursor 形态不合法");
    }
}
