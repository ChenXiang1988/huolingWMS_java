package com.huoling.wms.masterdata.archive.domain;

import com.huoling.wms.masterdata.domain.SkuArchiveDedupRule;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 商品档案仓储。数据域在实现层注入：货主域方法一律带 {@code ownerId} 参数，
 * 实现里把它拼成 {@code where owner_id = ?}，业务代码不写隔离条件（INV-06）。
 */
public interface SkuArchiveRepository {

    /** 全量视图按系统编号取，运营端使用。 */
    Optional<SkuArchive> findByArchiveNo(String archiveNo);

    /** 货主域按系统编号取；{@code ownerId} 为空直接按越权拒绝，不回落成全量。 */
    Optional<SkuArchive> findByArchiveNoAndOwner(String archiveNo, Long ownerId);

    /** 批量取（批量审核用），全量视图。 */
    List<SkuArchive> findByArchiveNos(Collection<String> archiveNos);

    /** 同货主下的档案身份快照，供 Kernel 判重。 */
    List<SkuArchiveDedupRule.SkuIdentity> findIdentities(Long ownerId);

    /** 新增。归属与系统编号只在此写入，之后不再更新。 */
    void insert(SkuArchive archive, long operatorId);

    /**
     * 带乐观锁的更新：只有库中版本仍等于 {@code expectedVersion} 才写入。
     *
     * @return {@code false} 表示版本已被他人推进，调用方按 {@code CONFLICT_VERSION} 处理
     */
    boolean update(SkuArchive archive, long expectedVersion, long operatorId);

    /** 键集分页，返回条数不超过 {@code query.limit()}，是否还有下一页由调用方多取一条判定。 */
    List<SkuArchive> page(SkuArchiveQuery query);
}
