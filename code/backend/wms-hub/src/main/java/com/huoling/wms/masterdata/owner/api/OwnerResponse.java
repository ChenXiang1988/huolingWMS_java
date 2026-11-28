package com.huoling.wms.masterdata.owner.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;

import java.util.List;
import java.util.UUID;

/**
 * 货主档案接口响应体：{@code code}、{@code message}、{@code data}、{@code traceId}、{@code retryable}
 * 五个必填字段（契约 1.1「错误响应结构」），另有 {@code retryAfterMs} 与 {@code details} 两个可带字段。
 *
 * <p>为什么 owner 包另有一份响应体而不复用商品档案的：架构断言要求
 * {@code masterdata.owner} 与 {@code masterdata.archive} 双向不得依赖（design AR-11），
 * 复用对方的响应类会直接违反该规则。线上结构完全一致——一个结构、不按端分叉是契约对
 * JSON 形状的承诺，不是对 Java 类型的承诺；两边的字段名与省略规则逐字对齐即可。
 *
 * <p>{@code details} 是本变更在响应包上补齐的字段（契约 1.1、1.10）：元素为
 * {@code {field, reason}}，只有字段级错误才出现，{@code field} 与契约字段同名。
 * 空值不输出，成功响应不带该字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OwnerResponse<T>(String code, String message, T data, String traceId, boolean retryable,
                               Long retryAfterMs, List<OwnerFields.Issue> details) {

    public static final String OK = "0";

    public static <T> OwnerResponse<T> ok(T data) {
        return new OwnerResponse<>(OK, "ok", data, newTraceId(), false, null, null);
    }

    public static <T> OwnerResponse<T> failure(String code, String message, boolean retryable) {
        return new OwnerResponse<>(code, message, null, newTraceId(), retryable, null, null);
    }

    /** 可重试失败的响应体：限流、处理中与依赖不可用带上重试间隔。 */
    public static <T> OwnerResponse<T> retryableFailure(String code, String message,
                                                        long retryAfterMs) {
        return new OwnerResponse<>(code, message, null, newTraceId(), true, retryAfterMs, null);
    }

    /** 字段级错误：{@code details} 按契约 1.1 给出 {@code field} 与 {@code reason}。 */
    public static <T> OwnerResponse<T> invalid(String message, List<OwnerFields.Issue> details) {
        return new OwnerResponse<>(OwnerErrorCodes.PARAM_INVALID, message, null, newTraceId(), false,
            null, details == null || details.isEmpty() ? null : List.copyOf(details));
    }

    private static String newTraceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
