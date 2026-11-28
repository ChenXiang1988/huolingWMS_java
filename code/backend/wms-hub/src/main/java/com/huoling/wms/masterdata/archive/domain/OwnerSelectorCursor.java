package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerListSortKey;
import com.huoling.wms.masterdata.domain.OwnerSortDirection;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 货主选择器（API-020）的键集分页游标：锚在（排序键值, 货主编号）上。
 *
 * <p>为什么不复用档案列表的 {@code OwnerPageCursor}：两个包不得互相依赖（design AR-11），
 * 而契约 1.1 明确「游标不跨接口通用」——每条接口的游标是实现细节，本就允许各自编码。
 * 两处锚点语义一致（排序键 + 货主编号），换排序键后旧游标同样被拒绝。
 */
public record OwnerSelectorCursor(OwnerListSortKey key, OwnerSortDirection direction,
                                  String ownerNo, String value) {

    private static final String SEPARATOR = "|";
    private static final int PARTS = 4;

    public OwnerSelectorCursor {
        if (key == null || direction == null || ownerNo == null || ownerNo.isBlank()
            || value == null) {
            throw invalid();
        }
    }

    /** 文本锚点：货主编号作为排序键时值等于编号本身。 */
    public static OwnerSelectorCursor of(OwnerListSort sort, String value, String ownerNo) {
        return new OwnerSelectorCursor(sort.key(), sort.direction(), ownerNo,
            value == null ? "" : value);
    }

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
     * @throws ArchiveException 形态不合法或与排序参数不一致时为 {@code PARAM_INVALID}
     */
    public static OwnerSelectorCursor decode(String raw, OwnerListSort sort) {
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
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "参数 cursor 与排序参数不一致，请回到第一页重新翻页");
        }
        return new OwnerSelectorCursor(key, direction, parts[2], parts[3]);
    }

    /** 是否升序。 */
    public boolean ascending() {
        return direction == OwnerSortDirection.ASC;
    }

    private static ArchiveException invalid() {
        return new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "参数 cursor 形态不合法");
    }
}
