package com.huoling.wms.masterdata.identity.api;

import java.util.List;

/**
 * 分页响应包装，与契约章一致：{@code {items, total, page, size}}。
 * 条目类型由各接口自己的响应 DTO 给出，标识字段已在条目里转为字符串。
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {
}
