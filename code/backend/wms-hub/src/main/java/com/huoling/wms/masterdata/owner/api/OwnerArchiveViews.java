package com.huoling.wms.masterdata.owner.api;

import com.huoling.wms.masterdata.domain.OwnerAction;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.owner.application.OwnerQueryService;
import com.huoling.wms.masterdata.owner.domain.OwnerArchive;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusChange;

import java.time.Instant;
import java.util.List;

/**
 * 货主档案的列表与详情视图（契约 API-021、API-022 的响应字段，逐字对齐 1.5 字段表）。
 *
 * <p>三条口径写在类型上：
 * <ul>
 *   <li>标识一律字符串：{@code ownerId}、{@code createdBy}、{@code updatedBy} 都是十进制字符串，
 *       不让大整数在调用方丢精度；</li>
 *   <li>时间一律 UTC ISO-8601：{@code Instant} 由 Jackson 序列化成带 {@code Z} 的字符串；</li>
 *   <li>列表只返回 {@code createdTime} 与 {@code updateTime}，{@code createdBy}、{@code updatedBy}
 *       与 {@code lastStatusChange} 只在详情里出现——列表行不足以渲染三段抽屉，
 *       多回字段会让前端以为行数据够用（design 前端章「状态与数据归属」）。</li>
 * </ul>
 */
final class OwnerArchiveViews {

    private OwnerArchiveViews() {
    }

    /** API-021 条目。 */
    record OwnerItem(String ownerId, String ownerNo, String ownerName, String contactName,
                     String contactPhone, String creditCode, OwnerStatus status, long version,
                     Instant createdTime, Instant updateTime) {

        static OwnerItem of(OwnerArchive archive) {
            return new OwnerItem(String.valueOf(archive.id()), archive.ownerNo(),
                archive.fields().ownerName(), archive.fields().contactName(),
                archive.fields().contactPhone(), archive.fields().creditCode(), archive.status(),
                archive.version(), archive.createdAt(), archive.updatedAt());
        }
    }

    /** API-021 列表响应：条目、下一页游标与是否还有数据；不带 {@code dataTimestamp}（读权威库）。 */
    record OwnerPage(List<OwnerItem> items, String nextCursor, boolean hasMore) {

        static OwnerPage of(OwnerQueryService.OwnerPage page) {
            return new OwnerPage(page.items().stream().map(OwnerItem::of).toList(),
                page.nextCursor(), page.hasMore());
        }
    }

    /** API-022 详情：在列表字段之上补审计列与最近一次状态变更。 */
    record OwnerDetail(String ownerId, String ownerNo, String ownerName, String contactName,
                       String contactPhone, String creditCode, OwnerStatus status, long version,
                       Instant createdTime, Instant updateTime, String createdBy, String updatedBy,
                       OwnerStatusChangeView lastStatusChange) {

        static OwnerDetail of(OwnerArchive archive, String operatorName) {
            OwnerStatusChange change = archive.lastStatusChange();
            return new OwnerDetail(String.valueOf(archive.id()), archive.ownerNo(),
                archive.fields().ownerName(), archive.fields().contactName(),
                archive.fields().contactPhone(), archive.fields().creditCode(), archive.status(),
                archive.version(), archive.createdAt(), archive.updatedAt(),
                String.valueOf(archive.createdBy()), String.valueOf(archive.updatedBy()),
                new OwnerStatusChangeView(OwnerStatusChangeView.Action.of(change.action()),
                    change.reason(), operatorName, change.changedAt()));
        }
    }

    /**
     * 最近一次状态变更（契约 1.5 的 {@code lastStatusChange}）。
     *
     * @param action   {@code CREATE} / {@code DISABLE} / {@code ENABLE}，见 {@link Action}
     * @param reason   停用与启用的原因；建档时为空
     * @param operator 操作人账号名（D24 的「人 + 端 + 对象标识」在本聚合上的退化形态）
     * @param changedAt UTC 时间
     */
    record OwnerStatusChangeView(Action action, String reason, String operator,
                                 Instant changedAt) {

        /**
         * 对外暴露的状态变更动作：只有契约 1.5 与 `21` E-19 的三个取值。
         *
         * <p>为什么不直接用 kernel 的 {@link OwnerAction}：那个枚举还有一个 {@code EDIT}，
         * 它是「不改状态的写动作」，只参与状态守卫、不写状态留痕。直接外露会让产物里的
         * {@code lastStatusChange.action} 多出一个契约没有的取值（F-4），调用方按闭集实现
         * 就会漏掉一个分支。收窄成独立枚举后，契约的闭集由类型本身保证；
         * 万一库里真的出现 {@code EDIT}（{@code ck_wms_owner_status_action} 已禁止），
         * 这里是显式失败而不是悄悄透出一个契约外取值。
         */
        enum Action {
            /** 建档。 */
            CREATE,
            /** 停用。 */
            DISABLE,
            /** 重新启用。 */
            ENABLE;

            /** 由 kernel 的动作收窄到对外三值；{@code EDIT} 不属于状态变更，按不变量违反处理。 */
            static Action of(OwnerAction source) {
                return switch (source) {
                    case CREATE -> CREATE;
                    case DISABLE -> DISABLE;
                    case ENABLE -> ENABLE;
                    case EDIT -> throw new IllegalStateException(
                        "改档不写状态留痕，lastStatusChange.action 只能是 CREATE／DISABLE／ENABLE");
                };
            }
        }
    }
}
