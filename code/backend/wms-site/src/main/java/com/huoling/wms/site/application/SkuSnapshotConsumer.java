package com.huoling.wms.site.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.domain.SkuSnapshotRule;
import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.DownlinkEventConsumer;
import com.huoling.wms.site.domain.SiteChangeRequestRepository;
import com.huoling.wms.site.domain.SkuSnapshot;
import com.huoling.wms.site.domain.SkuSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

/**
 * 下行消费：把 Hub 的档案事件落到本仓缓存，把审核结果落到本仓申请记录（tasks 1.10、1.12）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>按事件标识去重</b>：事件标识随消息属性传入，重复投递不会重复应用；</li>
 *   <li><b>版本落后即丢弃</b>：判定交给 kernel 的 {@link SkuSnapshotRule}，落库时再用
 *       「版本更大才覆盖」的条件更新兜一层，缓存版本永不倒退；</li>
 *   <li><b>保留数据时间戳</b>：写入的是权威数据的产生时间，不是消费时间，降级展示据此提示待刷新。</li>
 * </ol>
 *
 * <p>只缓存生效与停用两态：草稿、待审核、驳回不进本仓缓存（契约 API-010「本仓生效档案」）。
 */
@Service
public class SkuSnapshotConsumer implements DownlinkEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SkuSnapshotConsumer.class);

    public static final String TYPE_ARCHIVE_ACTIVATED = "SKU_ARCHIVE_ACTIVATED";
    public static final String TYPE_ARCHIVE_CHANGED = "SKU_ARCHIVE_CHANGED";
    public static final String TYPE_CHANGE_REQUEST_REVIEWED = "SKU_CHANGE_REQUEST_REVIEWED";

    private final SkuSnapshotRepository snapshots;
    private final SiteChangeRequestRepository changeRequests;
    private final SiteProperties properties;
    private final ObjectMapper objectMapper;

    public SkuSnapshotConsumer(SkuSnapshotRepository snapshots,
                               SiteChangeRequestRepository changeRequests,
                               SiteProperties properties,
                               ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.changeRequests = changeRequests;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费一条下行消息。
     *
     * @param eventId     事件标识（档案编号加版本号，或申请编号加结果），去重键
     * @param eventType   事件类型
     * @param payloadJson 下行摘要
     * @return 是否真的应用了这条消息（版本落后、状态不生效或非本仓都返回 {@code false}）
     */
    @Override
    public boolean consume(String eventId, String eventType, String payloadJson) {
        if (eventType == null) {
            log.warn("下行事件 {} 缺少事件类型，按未知事件丢弃", eventId);
            return false;
        }
        return switch (eventType) {
            case TYPE_ARCHIVE_ACTIVATED, TYPE_ARCHIVE_CHANGED -> applyArchive(eventId, payloadJson);
            case TYPE_CHANGE_REQUEST_REVIEWED -> applyReviewResult(eventId, payloadJson);
            default -> {
                log.warn("未知的下行事件类型 {}（{}），按未知事件丢弃", eventType, eventId);
                yield false;
            }
        };
    }

    private boolean applyArchive(String eventId, String payloadJson) {
        ArchivePayload payload = read(payloadJson, ArchivePayload.class);
        if (payload == null) {
            return false;
        }
        if (!"ACTIVE".equals(payload.status()) && !"DISABLED".equals(payload.status())) {
            log.info("档案 {} 状态 {} 不进本仓缓存，丢弃事件 {}", payload.archiveId(), payload.status(), eventId);
            return false;
        }
        Long siteId = localSite();
        if (siteId == null) {
            return false;
        }
        long cachedVersion = snapshots.find(siteId, payload.archiveId())
            .map(SkuSnapshot::version)
            .orElse(0L);
        if (!SkuSnapshotRule.shouldApply(cachedVersion, payload.version())) {
            // 版本落后或相等：保持当前缓存，界面据数据时间戳提示待刷新（specs product-archive-review）。
            log.info("档案 {} 的下行版本 {} 不高于已应用版本 {}，丢弃事件 {}",
                payload.archiveId(), payload.version(), cachedVersion, eventId);
            return false;
        }
        Instant now = Instant.now();
        snapshots.apply(new SkuSnapshot(null, siteId, parseId(payload.ownerId()), stableId(payload.archiveId()),
            payload.archiveId(), payload.ownerCode(), payload.name(), payload.barcode(),
            payload.baseUnit(), payload.storageCondition(), payload.category(), payload.spec(),
            payload.status(), payload.version(), eventId, payload.dataTimestamp(), now));
        return true;
    }

    private boolean applyReviewResult(String eventId, String payloadJson) {
        ReviewPayload payload = read(payloadJson, ReviewPayload.class);
        if (payload == null || payload.requestNo() == null) {
            return false;
        }
        Long siteId = localSite();
        if (siteId == null) {
            return false;
        }
        if (payload.siteId() != null && !payload.siteId().equals(siteId)) {
            // 不是本仓发起的申请：下发给所有单仓的消息里只有发起仓需要落地。
            return false;
        }
        changeRequests.applyReviewResult(payload.requestNo(), payload.status(), payload.reviewReason(),
            payload.archiveVersion(), eventId, Instant.now());
        return true;
    }

    private Long localSite() {
        Long siteId = properties.getSite().getId();
        if (siteId == null || siteId <= 0) {
            log.warn("本仓标识未配置（wms.site.id），下行事件无法落地");
            return null;
        }
        return siteId;
    }

    private <T> T read(String payloadJson, Class<T> type) {
        try {
            return objectMapper.readValue(payloadJson, type);
        } catch (JsonProcessingException e) {
            log.warn("下行摘要解析失败，按无效消息丢弃：{}", e.getMessage());
            return null;
        }
    }

    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code site_sku_snapshot.archive_id} 是 NOT NULL 的数值列（脚本已合入不可改），
     * 而下行摘要只带全局唯一的系统编号：这里按系统编号取稳定摘要填充，仅占位，不参与关联。
     */
    private long stableId(String archiveId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(archiveId.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int index = 0; index < 8; index++) {
                value = (value << 8) | (bytes[index] & 0xFFL);
            }
            return value & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 下行档案摘要（字段口径与 Hub 的 {@code ArchiveEventPayload} 一致）。 */
    record ArchivePayload(String archiveId, String ownerId, String ownerCode, String name,
                          String barcode, String baseUnit, String storageCondition, String category,
                          String spec, String status, long version, Instant dataTimestamp,
                          List<String> changedFields) {
    }

    /** 下行申请结果摘要（与 Hub 的 {@code ChangeRequestEventPayload} 一致）。 */
    record ReviewPayload(String requestNo, String archiveId, String status, String reviewReason,
                         Long archiveVersion, Long siteId, Instant dataTimestamp) {
    }
}
