package com.huoling.wms.masterdata.owner.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.huoling.wms.masterdata.domain.OwnerFields;

/**
 * 货主档案写接口的请求体（契约 API-023 至 API-026「请求」）。
 *
 * <p>未知字段一律拒绝，不静默忽略（契约 1.2「输入校验」）：拼错字段被当成「没改」比报错更危险。
 * 建档与改档的请求体都<b>不含</b> {@code ownerNo}、{@code status}、{@code version}（建档）——
 * 编号由系统生成且不可变、状态走独立动作，传入即按未知字段被拒。
 *
 * <p>{@code @JsonAnySetter} 抛出的异常会被 Spring 包成 {@code HttpMessageNotReadableException}，
 * 由 {@link OwnerExceptionHandler} 拆出 {@code field} 后按 {@code PARAM_INVALID} 返回。
 *
 * <p>三个请求体都带 {@code Owner} 前缀：契约产物按<b>简单名</b>归并 {@code components.schemas}，
 * {@code wms-hub} 里已有身份模块与商品档案各自的 {@code CreateRequest}／{@code EditRequest}，
 * 同名记录会让货主档案的建档请求体在产物里指向别人的字段集（F-3 的实证：
 * 建档曾指向账号开通、改档曾指向商品档案）。改名的唯一目的是让产物里的 schema 名不撞车。
 */
final class OwnerWriteRequests {

    private OwnerWriteRequests() {
    }

    /** API-023 请求体：四项必填。 */
    record OwnerCreateRequest(String ownerName, String contactName, String contactPhone,
                              String creditCode) {

        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw OwnerEntryChecks.unknownField(fieldName);
        }

        OwnerFields toFields() {
            return new OwnerFields(ownerName, contactName, contactPhone, creditCode);
        }
    }

    /** API-024 请求体：四项全量替换加版本号。 */
    record OwnerEditRequest(String ownerName, String contactName, String contactPhone,
                            String creditCode, Long version) {

        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw OwnerEntryChecks.unknownField(fieldName);
        }

        OwnerFields toFields() {
            return new OwnerFields(ownerName, contactName, contactPhone, creditCode);
        }
    }

    /** API-025、API-026 请求体：原因与版本号，两者必填。 */
    record OwnerReasonRequest(String reason, Long version) {

        @JsonAnySetter
        void rejectUnknownField(String fieldName, Object ignored) {
            throw OwnerEntryChecks.unknownField(fieldName);
        }
    }
}
