package com.huoling.wms.masterdata.owner.domain;

import java.util.List;
import java.util.Optional;

/**
 * 货主档案仓储。数据域在实现层注入：查询一律带 {@link OwnerScope}，
 * 实现里把它拼成 {@code id in (...)} 或无条件，业务代码不手写隔离条件（INV-19）。
 *
 * <p>接口不提供 {@code delete}：货主档案不物理删除，停用以状态表达（OI-06、INV-18）。
 */
public interface OwnerArchiveRepository {

    /**
     * 按货主编号取，且只在该账号的货主域内可见。
     *
     * <p>域为空一律 {@code FORBIDDEN_OWNER}，不回落成「全部」（OI-08）；
     * 域外目标按不存在处理，不泄露存在性（契约 1.2「越权表现」）。
     *
     * @return 不存在或不在域内返回 {@link Optional#empty()}
     */
    Optional<OwnerArchive> findByOwnerNo(String ownerNo, OwnerScope scope);

    /**
     * 列表：四类筛选、排序与键集游标（契约 API-021）。
     *
     * <p>数据域由实现拼进条件；多取一条由调用方传 {@code limit + 1} 的写法完成，
     * 仓储只负责按条件取行。
     */
    List<OwnerArchive> page(OwnerArchiveQuery query);

    /**
     * 新增。编号与主键只在此写入，之后不再更新。
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 信用代码唯一索引冲突
     */
    void insert(OwnerArchive archive);

    /**
     * 带乐观锁的更新：只有库中版本仍等于 {@code expectedVersion} 才写入。
     *
     * @return {@code false} 表示版本已被他人推进，调用方按 {@code CONFLICT_VERSION} 处理
     */
    boolean update(OwnerArchive archive, long expectedVersion);
}
