package com.huoling.wms.masterdata.domain;

import java.util.List;
import java.util.Objects;

/**
 * 货主内判重（`26` 3.3）：同一货主内两个 SKU 的货主系统编码或条码相同即拒绝提交，
 * 不同货主填同一个编码或条码合法放行。
 *
 * <p>为什么排除草稿与停用：草稿态允许重复（货主可能正在改），停用后同编码可被新草稿使用；
 * 这与数据库侧的部分唯一索引条件一致（本次变更 design「强制手段分层」），
 * 应用层与索引层用同一个条件，避免一边放行一边报唯一约束冲突。
 *
 * <p>本类只做判定，不含查询：候选集由仓储层按 {@code owner_id} 取回后传入，
 * 货主条件不在业务代码里拼（INV-06）。
 */
public final class SkuArchiveDedupRule {

    private SkuArchiveDedupRule() {
    }

    /**
     * 同货主内的档案身份快照。
     *
     * @param id        档案主键，用于排除自身
     * @param status    当前状态，草稿与停用不参与判重
     * @param ownerCode 货主系统编码
     * @param barcode   条码
     */
    public record SkuIdentity(Long id, SkuArchiveStatus status, String ownerCode, String barcode) {
    }

    /**
     * 判重：候选身份中若存在同编码或同条码的生效竞争者，拒绝。
     *
     * @param selfId    当前档案主键，新建时为 {@code null}
     * @param ownerCode 待提交的货主系统编码
     * @param barcode   待提交的条码
     * @param existing  同一货主下的其他档案
     * @throws SkuRuleViolation 编码重复 {@code RULE_DUPLICATE_CODE} 或条码重复 {@code RULE_DUPLICATE_BARCODE}
     */
    public static void requireUnique(Long selfId, String ownerCode, String barcode,
                                     List<SkuIdentity> existing) {
        if (existing == null || existing.isEmpty()) {
            return;
        }
        String code = trim(ownerCode);
        String bar = trim(barcode);
        for (SkuIdentity other : existing) {
            if (other == null || Objects.equals(other.id(), selfId)) {
                continue;
            }
            if (countsInUniqueness(other.status())) {
                if (!code.isEmpty() && code.equals(trim(other.ownerCode()))) {
                    throw new SkuRuleViolation(SkuRuleCodes.RULE_DUPLICATE_CODE,
                        "货主内编码 " + code + " 已被档案 " + other.id() + " 占用");
                }
                if (!bar.isEmpty() && bar.equals(trim(other.barcode()))) {
                    throw new SkuRuleViolation(SkuRuleCodes.RULE_DUPLICATE_BARCODE,
                        "货主内条码 " + bar + " 已被档案 " + other.id() + " 占用");
                }
            }
        }
    }

    /** 参与唯一的判重范围：草稿与停用不参与，其余参与。 */
    public static boolean countsInUniqueness(SkuArchiveStatus status) {
        return status != null && status != SkuArchiveStatus.DRAFT && status != SkuArchiveStatus.DISABLED;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
