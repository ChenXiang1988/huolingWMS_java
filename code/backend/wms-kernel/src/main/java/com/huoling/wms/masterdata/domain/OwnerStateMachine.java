package com.huoling.wms.masterdata.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 货主档案状态机守卫：唯一允许的迁移集合写死在这里，Hub 与 Site 只能调用（不变量只在这里定义）。
 *
 * <p>允许的迁移（本次变更 design「状态迁移表」逐条对应）：
 * <ul>
 *   <li>建档：无档案 → 可用（{@code CREATE}）</li>
 *   <li>改档：可用 + {@code EDIT} → 可用（状态不变；停用态由守卫拒绝）</li>
 *   <li>停用：可用 + {@code DISABLE} → 停用</li>
 *   <li>启用：停用 + {@code ENABLE} → 可用</li>
 * </ul>
 *
 * <p>明确拒绝：停用态改档、重复停用、对可用再启用、重复建档，以及任何「系统自动恢复可用」的迁移
 * （没有定时任务、重跑或初始化路径能产生 {@code DISABLED → ACTIVE}，OI-12）。
 * 未列举的迁移一律抛 {@link OwnerRuleCodes#STATE_INVALID}，不做默认放行。
 */
public final class OwnerStateMachine {

    private static final Map<OwnerStatus, Map<OwnerAction, OwnerStatus>> TRANSITIONS =
        new EnumMap<>(OwnerStatus.class);

    static {
        Map<OwnerAction, OwnerStatus> fromActive = new EnumMap<>(OwnerAction.class);
        fromActive.put(OwnerAction.EDIT, OwnerStatus.ACTIVE);
        fromActive.put(OwnerAction.DISABLE, OwnerStatus.DISABLED);

        Map<OwnerAction, OwnerStatus> fromDisabled = new EnumMap<>(OwnerAction.class);
        fromDisabled.put(OwnerAction.ENABLE, OwnerStatus.ACTIVE);

        TRANSITIONS.put(OwnerStatus.ACTIVE, fromActive);
        TRANSITIONS.put(OwnerStatus.DISABLED, fromDisabled);
    }

    private OwnerStateMachine() {
    }

    /**
     * 判定动作在给定状态下是否允许，返回迁移后的状态。
     *
     * @param current 当前状态；{@code null} 表示档案尚不存在，只允许 {@code CREATE}
     * @throws OwnerRuleViolation 迁移不在守卫表内，规则码 {@code STATE_INVALID}
     */
    public static OwnerStatus transit(OwnerStatus current, OwnerAction action) {
        if (action == null) {
            throw new OwnerRuleViolation(OwnerRuleCodes.PARAM_INVALID, "缺少动作");
        }
        if (current == null) {
            if (action == OwnerAction.CREATE) {
                return OwnerStatus.ACTIVE;
            }
            throw new OwnerRuleViolation(OwnerRuleCodes.STATE_INVALID,
                "档案尚未创建，只允许建档，收到动作 " + action);
        }
        if (action == OwnerAction.CREATE) {
            throw new OwnerRuleViolation(OwnerRuleCodes.STATE_INVALID, "档案已存在，不允许再次建档");
        }
        OwnerStatus target = TRANSITIONS.getOrDefault(current, Map.of()).get(action);
        if (target == null) {
            throw new OwnerRuleViolation(OwnerRuleCodes.STATE_INVALID,
                "状态 " + current + " 不允许动作 " + action);
        }
        return target;
    }

    /** 只判定不抛异常，供查询侧拼「可执行动作」用。 */
    public static boolean allows(OwnerStatus current, OwnerAction action) {
        if (action == null) {
            return false;
        }
        if (current == null) {
            return action == OwnerAction.CREATE;
        }
        return TRANSITIONS.getOrDefault(current, Map.of()).containsKey(action);
    }

    /** 给定状态下允许的动作集合，供契约响应里回显可用动作。 */
    public static Set<OwnerAction> allowedActions(OwnerStatus current) {
        if (current == null) {
            return EnumSet.of(OwnerAction.CREATE);
        }
        Map<OwnerAction, OwnerStatus> actions = TRANSITIONS.getOrDefault(current, Map.of());
        return actions.isEmpty() ? Set.of() : EnumSet.copyOf(actions.keySet());
    }
}
