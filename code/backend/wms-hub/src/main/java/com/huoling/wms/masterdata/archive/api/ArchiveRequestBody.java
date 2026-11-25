package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;

/**
 * 档案写接口请求体的入口拒绝（契约 API-001「请求：未知字段拒绝」、design「鲁棒性约定」）。
 *
 * <p>为什么不是「认识的字段绑定、不认识的忽略」：忽略未知字段会让 {@code locationId} 这类
 * 不属于商品档案模型的库位关联字段被静默吞掉，调用方以为写成功、实际没写。
 * 档案接口一律按参数错误拒绝，且只作用于档案控制器：身份模块的请求体绑定行为不变。
 *
 * <p>实现方式：请求体 record 各带一个 {@code @JsonAnySetter} 方法，Jackson 只在遇到
 * 未声明字段时调用它，这里直接抛契约错误码。Spring 会把反序列化失败包成
 * {@code HttpMessageNotReadableException}，由 {@link ArchiveExceptionHandler} 翻成 400
 * {@code PARAM_INVALID}，响应包与其余档案错误一致。
 */
final class ArchiveRequestBody {

    private ArchiveRequestBody() {
    }

    /**
     * 未知字段的契约异常。
     *
     * @param fieldName 请求体里出现但档案模型不认识的字段名
     * @return 契约错误码 {@code PARAM_INVALID} 的领域异常，由 {@code @JsonAnySetter} 方法抛出
     */
    static ArchiveException unknownField(String fieldName) {
        return new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
            "请求体含未知字段 " + fieldName + "：档案接口只接受契约声明的字段，不接受库位关联字段");
    }
}
