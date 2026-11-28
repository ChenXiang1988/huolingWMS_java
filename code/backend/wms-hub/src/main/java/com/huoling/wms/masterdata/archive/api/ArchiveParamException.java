package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;

import java.util.List;

/**
 * 入口字段级错误：携带契约 1.1 的 {@code details} 明细（{@code field} + {@code reason}）。
 *
 * <p>为什么放在接口层而不是领域层：{@code details} 是响应体结构，领域层不带 HTTP 与响应语义；
 * {@code field} 又只对调用方有意义。{@link ArchiveExceptionHandler} 按本类型输出
 * {@code details}，其余档案错误的行为完全不变。
 *
 * <p>当前唯一的使用者是 API-020 的两个已废弃参数（{@code ownerCode}、{@code name}）：
 * 契约要求 {@code details[].field} 给出旧名（便于调用方按字段名改成 {@code ownerNo} /
 * {@code ownerName}），而一句 400 说不清是哪一项。
 */
class ArchiveParamException extends ArchiveException {

    private final List<ArchiveResponse.Detail> details;

    private ArchiveParamException(List<ArchiveResponse.Detail> details, String message) {
        super(ArchiveErrorCodes.PARAM_INVALID, message);
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    /** 单字段的一条明细。 */
    static ArchiveParamException of(String field, String reason) {
        return new ArchiveParamException(List.of(new ArchiveResponse.Detail(field, reason)), reason);
    }

    List<ArchiveResponse.Detail> details() {
        return details;
    }
}
