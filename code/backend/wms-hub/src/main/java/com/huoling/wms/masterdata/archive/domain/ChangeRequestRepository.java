package com.huoling.wms.masterdata.archive.domain;

import java.util.List;
import java.util.Optional;

/** 变更申请仓储。数据域在实现层注入：单仓域方法带 {@code warehouseId}，运营端全量不过滤。 */
public interface ChangeRequestRepository {

    Optional<ChangeRequest> findByRequestNo(String requestNo);

    /** 本仓域按申请编号取；越仓按越权拒绝，不泄露对方申请内容。 */
    Optional<ChangeRequest> findByRequestNoAndWarehouse(String requestNo, Long warehouseId);

    /** 该档案是否已有待审核申请（同一档案仅一条）。 */
    boolean hasPending(Long archiveId);

    void insert(ChangeRequest request, long operatorId);

    /** 带乐观锁的更新，返回 {@code false} 表示版本已被推进。 */
    boolean update(ChangeRequest request, long expectedVersion, long operatorId);

    /** 键集分页。 */
    List<ChangeRequest> page(ChangeRequestQuery query);
}
