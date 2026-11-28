package com.huoling.wms.site.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.site.domain.SiteException;
import com.huoling.wms.site.domain.SiteOwnerStatus;
import com.huoling.wms.site.domain.SiteOwnerStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 货主状态下行消费（tasks 1.14；契约 1.9、design「消费幂等」）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>按主键 upsert</b>：同一 {@code eventId} 重复投递只更新同一行，不产生第二行；</li>
 *   <li><b>按版本号单调推进</b>：乱序到达的旧事件被丢弃（判定落在 SQL 的条件更新上，
 *       不在应用层先读后比——先读后比在并发消费下会把旧状态写回去）；</li>
 *   <li><b>保留数据时间戳</b>：写入的是权威数据的产生时间（{@code changedAt}），
 *       不是消费时间；闸门据它判断副本是否已过陈旧阈值。</li>
 * </ol>
 *
 * <p>只缓存「可用 / 停用」两态（与 kernel {@link OwnerStatus} 同一闭集）：未知状态按参数错误抛出，
 * 由监听器拒绝消息并转死信队列——**不静默丢弃**，否则一次上游字段漂移会表现成「闸门一直拒绝」而无人知晓。
 *
 * <p>事件类型常量在这里再写一份（不从 Hub 引用）：四个可启动单元互不依赖（AR-03），
 * 与 {@code SkuSnapshotConsumer} 处理既有下行的做法一致。
 */
@Service
public class OwnerStatusConsumer {

    private static final Logger log = LoggerFactory.getLogger(OwnerStatusConsumer.class);

    /** 货主状态变更事件名（与契约 1.9 的代码常量一致）。 */
    public static final String TYPE_OWNER_STATUS_CHANGED = "owner.status.changed";

    private final SiteOwnerStatusRepository statuses;
    private final ObjectMapper objectMapper;

    public OwnerStatusConsumer(SiteOwnerStatusRepository statuses, ObjectMapper objectMapper) {
        this.statuses = statuses;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费一条下行消息。
     *
     * @param eventId     消息属性里的事件标识（去重键），载荷里也有同名字段
     * @param eventType   事件类型；本消费者只认货主状态变更
     * @param payloadJson 下行摘要
     * @return 是否真的应用了这条消息（版本落后、或不是本消费者处理的事件类型返回 {@code false}）
     * @throws SiteException 载荷不合法（{@code PARAM_INVALID}），由监听器拒绝并转死信队列
     */
    public boolean consume(String eventId, String eventType, String payloadJson) {
        if (!TYPE_OWNER_STATUS_CHANGED.equalsIgnoreCase(eventType)) {
            log.warn("非货主状态变更事件 {}（{}），按未知事件丢弃", eventType, eventId);
            return false;
        }
        OwnerStatusPayload payload = read(payloadJson, eventId);
        String eventKey = eventId == null || eventId.isBlank() ? payload.eventId() : eventId;
        SiteOwnerStatus status = new SiteOwnerStatus(payload.ownerId(), payload.ownerNo(),
            payload.status(), payload.version(), eventKey, payload.changedAt(), Instant.now());
        boolean applied = statuses.apply(status);
        if (applied) {
            log.info("货主 {} 状态同步为 {}（版本 {}，事件 {}）", payload.ownerNo(),
                payload.status(), payload.version(), eventKey);
        } else {
            log.info("货主 {} 的下行版本 {} 不高于已应用版本，丢弃事件 {}", payload.ownerNo(),
                payload.version(), eventKey);
        }
        return applied;
    }

    private OwnerStatusPayload read(String payloadJson, String eventId) {
        Payload raw;
        try {
            raw = objectMapper.readValue(payloadJson, Payload.class);
        } catch (JsonProcessingException e) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "货主状态下行摘要解析失败：" + e.getOriginalMessage());
        }
        if (raw == null) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID, "货主状态下行摘要为空");
        }
        long ownerId = parseOwnerId(raw.ownerId(), eventId);
        if (raw.ownerNo() == null || raw.ownerNo().isBlank()) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "货主状态下行摘要缺 ownerNo（" + eventId + "）");
        }
        OwnerStatus status = parseStatus(raw.status(), eventId);
        if (raw.version() < 0) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "货主状态下行摘要的 version 不能为负（" + eventId + "）");
        }
        if (raw.changedAt() == null) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "货主状态下行摘要缺 changedAt，无法判断副本是否陈旧（" + eventId + "）");
        }
        return new OwnerStatusPayload(ownerId, raw.ownerNo(), status, raw.version(),
            raw.changedAt(), raw.eventId());
    }

    /** {@code ownerId} 是十进制字符串（契约 1.5），单仓端只做形态与取值范围校验。 */
    private long parseOwnerId(String raw, String eventId) {
        if (raw == null || !raw.matches("^[1-9][0-9]*$")) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "货主状态下行摘要的 ownerId 必须为十进制字符串（" + eventId + "）");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "货主状态下行摘要的 ownerId 超出取值范围（" + eventId + "）");
        }
    }

    /** 状态闭集交给 kernel 的 {@link OwnerStatus}，单仓端不另判一套两态。 */
    private OwnerStatus parseStatus(String raw, String eventId) {
        try {
            return OwnerStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SiteException(ArchiveContractCodes.PARAM_INVALID,
                "货主状态下行摘要的 status 只能是 ACTIVE 或 DISABLED（" + eventId + "）");
        }
    }

    /** 下行载荷（与 Hub 的 {@code OwnerStatusChangedEvent} 字段逐字一致，不含手机号与信用代码）。 */
    record Payload(String eventId, String ownerId, String ownerNo, String status, String action,
                   Instant changedAt, long version) {
    }

    /** 校验后的载荷：标识已转数值、状态已转枚举。 */
    private record OwnerStatusPayload(long ownerId, String ownerNo, OwnerStatus status, long version,
                                      Instant changedAt, String eventId) {
    }
}
