package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerListSortKey;
import com.huoling.wms.masterdata.domain.OwnerNumberRule;
import com.huoling.wms.masterdata.domain.OwnerRuleViolation;
import com.huoling.wms.masterdata.domain.OwnerSortDirection;
import com.huoling.wms.masterdata.owner.domain.OwnerParamException;

import java.util.List;

/**
 * 写接口入口的同步校验（tasks 1.10；契约 1.1、1.2 与 1.4 的 {@code PARAM_INVALID} 一行）。
 *
 * <p>为什么必须在入口挡住：字段缺失、未知字段、已废弃参数与超限请求一旦进了异步受理，
 * 调用方拿到的只是「已受理」，真正的参数错误要等轮询才出现，{@code PARAM_INVALID} 的语义
 * 也会从「请求没通过校验」变成「异步执行失败」。
 *
 * <p>边界只到字段级：四项必填、原因必填、版本号的「在不在」与「非负」在入口判定；
 * 版本号是否最新、目标是否存在、状态是否允许、信用代码是否重复一律留到受理后
 * （design「业务校验的时点」）。字段规则本身仍只有 kernel 一处口径，这里只调
 * {@link OwnerFields#issues()} 与 {@link OwnerNumberRule}，不另写一套。
 *
 * <p>每条拒绝都带 {@code details[].field}（契约 1.1）：{@code field} 与契约字段同名，
 * 前端据此定位到具体控件。
 */
final class OwnerEntryChecks {

    /** 契约 1.1：`cursor` 长度上限 512。 */
    static final int CURSOR_MAX = 512;
    /**
     * 契约 1.1：单请求体上限 16 KB。
     *
     * <p>判定在 {@link OwnerBodySizeFilter} 里做（读 {@code Content-Length}，不解析请求体）；
     * 常量留在这里，是因为它属于入口校验的口径，与字段规则、游标长度同处一表。
     */
    static final long BODY_MAX_BYTES = 16L * 1024L;

    private OwnerEntryChecks() {
    }

    /** 路径参数 {@code ownerNo}：形态由 kernel 判定，失败时给出字段名。 */
    static String requireOwnerNo(String ownerNo) {
        try {
            OwnerNumberRule.requireValid(ownerNo);
        } catch (RuntimeException e) {
            throw OwnerParamException.of("ownerNo", messageOf(e));
        }
        return ownerNo;
    }

    /** 四项必填与形态：逐字段明细一次回出，不逐条挤牙膏。 */
    static OwnerFields requireFields(OwnerFields fields) {
        if (fields == null) {
            throw OwnerParamException.of("body", "请求体缺失");
        }
        List<OwnerFields.Issue> issues = fields.issues();
        if (!issues.isEmpty()) {
            throw new OwnerParamException(issues, issues.get(0).reason());
        }
        return fields;
    }

    /** 停用与启用原因：必填、1 至 200 字。 */
    static String requireReason(String reason) {
        java.util.Optional<OwnerFields.Issue> issue = OwnerFields.reasonIssue(reason);
        if (issue.isPresent()) {
            throw OwnerParamException.of(issue.get().field(), issue.get().reason());
        }
        return reason;
    }

    /**
     * 版本号：必填且非负。
     *
     * <p>只判「有没有、合不合法」，不判「是不是最新」——后者在受理后按库中版本比对
     * （{@code CONFLICT_VERSION} 走 API-027）。
     */
    static Long requireVersion(Long version) {
        if (version == null) {
            throw OwnerParamException.of("version", "字段 version 必填");
        }
        if (version < 0) {
            throw OwnerParamException.of("version", "字段 version 不得为负数");
        }
        return version;
    }

    /** 每页条数：1 到 100（契约 1.1「分页」）。 */
    static int requireLimit(int limit) {
        if (limit <= 0 || limit > 100) {
            throw OwnerParamException.of("limit", "参数 limit 取值 1 到 100");
        }
        return limit;
    }

    /** 游标长度上限 512，超长在入口拒绝（契约 1.2「资源」）。 */
    static String requireCursor(String cursor) {
        if (cursor != null && cursor.length() > CURSOR_MAX) {
            throw OwnerParamException.of("cursor", "参数 cursor 长度上限 " + CURSOR_MAX);
        }
        return cursor;
    }

    /** 未知字段一律拒绝（契约 1.2「输入校验」），{@code field} 即出现的字段名。 */
    static OwnerParamException unknownField(String fieldName) {
        return OwnerParamException.of(fieldName,
            "请求体含未知字段 " + fieldName + "：货主档案接口只接受契约声明的字段");
    }

    /**
     * API-020 的两个已废弃参数（契约 1.8 API-020「BREAKING」）。
     *
     * <p>不设过渡别名：旧参数一旦被静默接受，调用方会继续传 {@code ownerCode}，
     * 而货主档案里没有这个字段，检索结果会长期是空的。{@code details[].field} 给出
     * 旧名与新名的对应，调用方按字段名即可改。
     */
    static void rejectDeprecatedSelectorParams(String ownerCode, String name) {
        if (ownerCode != null) {
            throw OwnerParamException.of("ownerCode",
                "参数 ownerCode 已废弃，请改用 ownerNo（货主编号精确匹配）");
        }
        if (name != null) {
            throw OwnerParamException.of("name",
                "参数 name 已废弃，请改用 ownerName（货主名称模糊匹配）");
        }
    }

    /**
     * 排序参数：白名单与方向闭集在 kernel 解析，字段名在这里定位（契约 1.1「排序与筛选」）。
     *
     * <p>分成两次解析而不是一次 {@code OwnerListSort.parse}：拒绝时要能说出是 {@code sortBy}
     * 还是 {@code sortDir}，否则调用方只能两个都猜一遍。
     */
    static OwnerListSort requireSort(String sortBy, String sortDir) {
        OwnerListSortKey key;
        try {
            key = OwnerListSortKey.parse(sortBy);
        } catch (OwnerRuleViolation e) {
            throw OwnerParamException.of("sortBy", messageOf(e));
        }
        OwnerSortDirection direction;
        try {
            direction = OwnerSortDirection.parse(sortDir);
        } catch (OwnerRuleViolation e) {
            throw OwnerParamException.of("sortDir", messageOf(e));
        }
        return new OwnerListSort(key, direction);
    }

    private static String messageOf(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "参数 ownerNo 形态不合法" : message;
    }
}
