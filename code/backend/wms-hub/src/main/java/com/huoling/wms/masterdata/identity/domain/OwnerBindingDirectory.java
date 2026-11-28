package com.huoling.wms.masterdata.identity.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 身份模块看货主档案的只读目录（tasks 1.15；design「账号与登录」）。
 *
 * <p>只有三件事要用它：开通货主端账号时校验「货主存在」、登录与令牌校验时看「货主是否停用」、
 * 账号列表与详情回显「货主编号与名称」（契约 1.7 的四个响应字段）。
 *
 * <p>为什么是只读投影而不是引用货主聚合：身份模块只需要「存在 / 是否可用 / 编号与名称」三件事，
 * 把聚合拿进来就会让人顺手在身份模块里改货主状态。货主状态的权威源在 Hub 的
 * {@code masterdata.owner}，本目录只读 {@code wms_owner}，不写、不缓存业务结论。
 */
public interface OwnerBindingDirectory {

    /** 按货主标识取绑定信息；档案不存在返回空。 */
    Optional<OwnerBinding> find(long ownerId);

    /** 批量取，用于账号列表回显；不存在的标识不出现在结果里。 */
    List<OwnerBinding> find(Collection<Long> ownerIds);

    /**
     * 账号所属货主的绑定信息。
     *
     * @param ownerId   货主标识
     * @param ownerNo   货主编号（契约 1.5）
     * @param ownerName 货主名称（可重复，仅展示）
     * @param active    是否可用；停用的货主不允许其账号登录（契约 1.7）
     */
    record OwnerBinding(long ownerId, String ownerNo, String ownerName, boolean active) {
    }
}
