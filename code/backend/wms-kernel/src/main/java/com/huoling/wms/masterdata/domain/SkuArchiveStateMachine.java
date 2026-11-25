package com.huoling.wms.masterdata.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 商品档案状态机守卫：唯一允许的迁移集合写死在这里，Hub 与 Site 只能调用。
 *
 * <p>允许的迁移（本次变更 design「领域模型与状态机」逐条对应）：
 * <ul>
 *   <li>提交：草稿、驳回、停用 → 待审核</li>
 *   <li>审核通过：待审核 → 启用</li>
 *   <li>驳回：待审核 → 驳回</li>
 *   <li>停用：启用 → 停用</li>
 *   <li>编辑：只在草稿与驳回态允许，状态不变</li>
 * </ul>
 * 未列举的迁移一律抛 {@link SkuRuleCodes#STATE_INVALID}，不做「默认放行」。
 */
public final class SkuArchiveStateMachine {

    private static final Map<SkuArchiveStatus, Map<SkuArchiveAction, SkuArchiveStatus>> TRANSITIONS =
        new EnumMap<>(SkuArchiveStatus.class);

    static {
        Map<SkuArchiveAction, SkuArchiveStatus> fromDraft = new EnumMap<>(SkuArchiveAction.class);
        fromDraft.put(SkuArchiveAction.SUBMIT, SkuArchiveStatus.PENDING);
        fromDraft.put(SkuArchiveAction.EDIT, SkuArchiveStatus.DRAFT);

        Map<SkuArchiveAction, SkuArchiveStatus> fromPending = new EnumMap<>(SkuArchiveAction.class);
        fromPending.put(SkuArchiveAction.APPROVE, SkuArchiveStatus.ACTIVE);
        fromPending.put(SkuArchiveAction.REJECT, SkuArchiveStatus.REJECTED);

        Map<SkuArchiveAction, SkuArchiveStatus> fromRejected = new EnumMap<>(SkuArchiveAction.class);
        fromRejected.put(SkuArchiveAction.SUBMIT, SkuArchiveStatus.PENDING);
        fromRejected.put(SkuArchiveAction.EDIT, SkuArchiveStatus.REJECTED);

        Map<SkuArchiveAction, SkuArchiveStatus> fromActive = new EnumMap<>(SkuArchiveAction.class);
        fromActive.put(SkuArchiveAction.DISABLE, SkuArchiveStatus.DISABLED);

        Map<SkuArchiveAction, SkuArchiveStatus> fromDisabled = new EnumMap<>(SkuArchiveAction.class);
        fromDisabled.put(SkuArchiveAction.SUBMIT, SkuArchiveStatus.PENDING);

        TRANSITIONS.put(SkuArchiveStatus.DRAFT, fromDraft);
        TRANSITIONS.put(SkuArchiveStatus.PENDING, fromPending);
        TRANSITIONS.put(SkuArchiveStatus.REJECTED, fromRejected);
        TRANSITIONS.put(SkuArchiveStatus.ACTIVE, fromActive);
        TRANSITIONS.put(SkuArchiveStatus.DISABLED, fromDisabled);
    }

    private SkuArchiveStateMachine() {
    }

    /**
     * 判定动作在给定状态下是否允许，返回迁移后的状态。
     *
     * @throws SkuRuleViolation 迁移不在守卫表内，规则码 {@code STATE_INVALID}
     */
    public static SkuArchiveStatus transit(SkuArchiveStatus current, SkuArchiveAction action) {
        if (current == null) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "档案缺少当前状态");
        }
        if (action == null) {
            throw new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, "缺少动作");
        }
        SkuArchiveStatus target = TRANSITIONS.getOrDefault(current, Map.of()).get(action);
        if (target == null) {
            throw new SkuRuleViolation(SkuRuleCodes.STATE_INVALID,
                "状态 " + current + " 不允许动作 " + action);
        }
        return target;
    }

    /** 只判定不抛异常，供查询侧拼「可执行动作」用。 */
    public static boolean allows(SkuArchiveStatus current, SkuArchiveAction action) {
        return current != null && action != null
            && TRANSITIONS.getOrDefault(current, Map.of()).containsKey(action);
    }

    /** 给定状态下允许的动作集合，供契约响应里回显可用动作。 */
    public static Set<SkuArchiveAction> allowedActions(SkuArchiveStatus current) {
        Map<SkuArchiveAction, SkuArchiveStatus> actions =
            TRANSITIONS.getOrDefault(current, Map.of());
        if (actions.isEmpty()) {
            return Set.of();
        }
        return EnumSet.copyOf(actions.keySet());
    }
}
