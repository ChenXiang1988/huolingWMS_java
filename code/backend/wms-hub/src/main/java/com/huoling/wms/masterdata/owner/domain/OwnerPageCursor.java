package com.huoling.wms.masterdata.owner.domain;

import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerListSortKey;
import com.huoling.wms.masterdata.domain.OwnerSortDirection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 货主档案列表的键集分页游标：以（排序键值, 货主编号）为锚点，不用 offset（`11`「分页不用 offset」）。
 *
 * <p>为什么第二排序键是货主编号而不是主键：{@code idx_wms_owner_sort_name} 与
 * {@code idx_wms_owner_sort_updated} 的第二列都是 {@code owner_no}，锚点取同一列才能走索引；
 * 货主编号全局唯一，用它做次级键同样不重不漏。
 *
 * <p>游标对调用方是不透明字符串，Base64URL 里依次放：排序字段、方向、货主编号与排序键值。
 * 字段与方向随游标一起编码，一个游标只对产生它的那种排序有效：换了排序键还拿旧锚点续翻，
 * 会按另一种顺序做「大于/小于」比较，必然重复或漏记录，因此解码时按键与方向校验，
 * 不一致按参数错误拒绝，不回落成第一页。
 *
 * <p>时间值按 ISO-8601 原样编码，不截成毫秒：库里 {@code timestamptz} 带微秒，
 * 截断后同一行会再次落进下一页（翻页重复）。
 *
 * <p>排序键值放在最后一段：货主名称理论上可能含分隔符，只有把它放末段、
 * 用「限定段数」的方式切分，值里的分隔符才不会被误当成字段边界。
 */
public record OwnerPageCursor(OwnerListSortKey key, OwnerSortDirection direction, String ownerNo,
                              String value) {

    private static final String SEPARATOR = "|";
    private static final int PARTS = 4;

    public OwnerPageCursor {
        if (key == null || direction == null || ownerNo == null || ownerNo.isBlank()
            || value == null) {
            throw invalid();
        }
    }

    /** 时间排序的锚点（最后修改时间）。 */
    public static OwnerPageCursor ofTime(OwnerListSort sort, Instant time, String ownerNo) {
        if (time == null) {
            throw invalid();
        }
        return new OwnerPageCursor(sort.key(), sort.direction(), ownerNo, time.toString());
    }

    /** 文本排序的锚点（货主编号、货主名称）。 */
    public static OwnerPageCursor ofText(OwnerListSort sort, String text, String ownerNo) {
        return new OwnerPageCursor(sort.key(), sort.direction(), ownerNo, text == null ? "" : text);
    }

    /** 编码为不透明游标。 */
    public String encode() {
        String plain = key.name() + SEPARATOR + direction.name() + SEPARATOR + ownerNo
            + SEPARATOR + value;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码；空值表示第一页。
     *
     * @param raw  调用方回传的游标原文
     * @param sort 本次请求的排序参数；游标与它不一致即拒绝
     * @throws OwnerContractException 形态不合法或与排序参数不一致时为 {@code PARAM_INVALID}
     */
    public static OwnerPageCursor decode(String raw, OwnerListSort sort) {
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
        OwnerListSortKey key;
        OwnerSortDirection direction;
        try {
            key = OwnerListSortKey.valueOf(parts[0]);
            direction = OwnerSortDirection.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (sort == null || key != sort.key() || direction != sort.direction()) {
            throw new OwnerContractException(OwnerErrorCodes.PARAM_INVALID,
                "参数 cursor 与排序参数不一致，请回到第一页重新翻页");
        }
        return new OwnerPageCursor(key, direction, parts[2], parts[3]);
    }

    /** 时间型排序键值。 */
    public Instant instantValue() {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    /** 文本型排序键值。 */
    public String textValue() {
        return value;
    }

    private static OwnerContractException invalid() {
        return new OwnerContractException(OwnerErrorCodes.PARAM_INVALID, "参数 cursor 形态不合法");
    }
}
