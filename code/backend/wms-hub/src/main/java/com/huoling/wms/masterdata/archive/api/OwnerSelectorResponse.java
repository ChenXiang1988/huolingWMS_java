package com.huoling.wms.masterdata.archive.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * 货主选择器（API-020）的成功响应信封：与 {@link ArchiveResponse} 同一结构，<b>去掉 {@code dataTimestamp}</b>。
 *
 * <p>为什么要单独一个类型：契约 API-020「响应」明写「不返回 {@code dataTimestamp}」（读 Hub 权威库、无副本），
 * 契约 1.1「查询元信息」也要求正常查询不得伪造该字段。{@code dataTimestamp} 在 {@link ArchiveResponse}
 * 上是给降级查询用的可带字段，只要控制器返回 {@code ArchiveResponse<T>}，产物里的成功响应 schema
 * 就必然带上它（F-7 的实证：成功响应 schema 声明了该字段）。信封类型是编译期确定的，
 * 字段一旦声明就没法按接口关掉，所以选择器用这份不带该字段的信封；
 * 其余商品档案接口继续用 {@link ArchiveResponse}（它们的降级口径允许带 {@code dataTimestamp}）。
 *
 * <p>为什么放在 {@code masterdata.archive.api} 而不是复用货主档案包的 {@code OwnerResponse}：
 * 架构断言 AR-11 要求 {@code masterdata.owner} 与 {@code masterdata.archive} 双向不得依赖，
 * 选择器控制器在 {@code archive} 包里，引用对方的响应类会直接违规（{@code OwnerResponse}
 * 的文档里有同样的说明）。两份信封的字段名与省略规则逐字对齐——契约承诺的是 JSON 形状，不是 Java 类型。
 *
 * <p>只有成功路径用它（API-020 无受理、无字段级错误需要 {@code details} 之外的差别）；
 * 错误响应仍由档案侧的统一异常映射产出 {@link ArchiveResponse}——错误体允许带
 * {@code dataTimestamp}（契约 1.1「查询元信息」）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OwnerSelectorResponse<T>(String code, String message, T data, String traceId,
                                       boolean retryable, Long retryAfterMs,
                                       List<ArchiveResponse.Detail> details) {

    /** 成功码固定为字符串 {@code "0"}（契约 1.1「错误响应结构」）。 */
    public static final String OK = "0";

    public static <T> OwnerSelectorResponse<T> ok(T data) {
        return new OwnerSelectorResponse<>(OK, "ok", data, newTraceId(), false, null, null);
    }

    private static String newTraceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
