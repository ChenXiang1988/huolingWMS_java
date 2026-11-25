package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.archive.application.SkuReviewService;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;

import java.util.List;

/**
 * 写接口入口的同步校验（本次变更 tasks 1.17；契约「鲁棒性约定」与 design「鲁棒性约定」）。
 *
 * <p>为什么必须在入口挡住：字段缺失、长度越界与枚举闭集外的请求一旦进了异步受理，
 * 调用方拿到的只是「已受理」，真正的参数错误要等轮询到受理查询才出现，既拖长链路，
 * 也让 {@code PARAM_INVALID} 的语义从「请求没通过校验」变成「异步执行失败」。
 * 这里校验失败直接抛 {@code PARAM_INVALID}（400），不落受理记录。
 *
 * <p>字段长度上限与库列宽一致（{@code hub_sku_archive.archive_no}、
 * {@code hub_product_change_request.request_no}、原因与目标值列均为 512），
 * 超长在入口就拒绝，不让数据库以 22001 报错兜底。
 *
 * <p>档案字段自身的必填、长度与数值形态不在这里重复判定：那是 Kernel
 * {@code SkuArchiveFields.requireComplete()} 的职责，入口只调它，不另写一套。
 */
final class ArchiveEntryChecks {

    /** 系统编号列宽：档案编号、申请编号与受理号同为 {@code varchar(32)}。 */
    static final int ID_MAX = 32;
    /** 原因列宽：驳回原因、停用原因与申请原因同为 {@code varchar(512)}。 */
    static final int REASON_MAX = 512;
    /** 变更目标值列宽。 */
    static final int TARGET_VALUE_MAX = 512;

    private ArchiveEntryChecks() {
    }

    /** 路径参数或请求体里的系统编号：非空且不超过列宽。 */
    static String requireId(String raw, String field) {
        return requireText(raw, field, ID_MAX);
    }

    /** 必填文本：去空白后非空，且不超过给定长度。返回值保留原样，指纹与下游都用同一份内容。 */
    static String requireText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "字段 " + field + " 必填");
        }
        if (raw.length() > maxLength) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "字段 " + field + " 长度上限 " + maxLength);
        }
        return raw;
    }

    /**
     * 乐观锁版本号：必填且非负。
     *
     * <p>契约把 {@code version} 定义为「整数，非负」；缺版本在入口就拒绝，
     * 不留到执行阶段再以 {@code PARAM_INVALID} 出现在受理结果里。
     */
    static Long requireVersion(Long version, String field) {
        if (version == null) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "字段 " + field + " 必填");
        }
        if (version < 0) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "字段 " + field + " 不得为负数");
        }
        return version;
    }

    /** 单条审核的驳回原因：动作是驳回时必填，通过时不带也不校验。 */
    static void requireReviewReason(SkuArchiveAction action, String reviewReason) {
        if (action == SkuArchiveAction.REJECT) {
            requireText(reviewReason, "reviewReason", REASON_MAX);
        }
    }

    /** 批量动作的原因：驳回与停用时必填（契约 API-009「请求」），通过时不带。 */
    static void requireBatchReason(SkuArchiveAction action, String reason) {
        if (action == SkuArchiveAction.REJECT || action == SkuArchiveAction.DISABLE) {
            requireText(reason, "reason", REASON_MAX);
        }
    }

    /**
     * 批量条目编号：非空、单次不超过一百、每条都是合法系统编号。
     *
     * <p>条数上限在入口判定，与 {@link SkuReviewService#BATCH_MAX} 同一口径，
     * 不在接口层另抄一个数字。
     */
    static List<String> requireBatchIds(List<String> archiveIds) {
        if (archiveIds == null || archiveIds.isEmpty()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "条目编号数组不能为空");
        }
        if (archiveIds.size() > SkuReviewService.BATCH_MAX) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "单次批量上限 " + SkuReviewService.BATCH_MAX + " 条");
        }
        for (String archiveId : archiveIds) {
            requireId(archiveId, "archiveIds");
        }
        return archiveIds;
    }
}
