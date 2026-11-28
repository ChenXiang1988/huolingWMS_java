package com.huoling.wms.site.support;

import com.huoling.wms.site.application.SiteAcceptanceService;
import com.huoling.wms.site.application.SiteChangeRequestService;
import com.huoling.wms.site.application.SkuSnapshotConsumer;
import com.huoling.wms.site.application.SkuSnapshotQueryService;
import com.huoling.wms.site.domain.SkuSnapshot;
import com.huoling.wms.site.domain.SkuSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单仓端集成测试支撑：连真 PostgreSQL（{@code wms_site} 库），造数自造自清。
 *
 * <p>下行缓存表上有 {@code uk_site_sku_snapshot_event} 与「版本更大才覆盖」的条件更新，
 * 换成内存库这两条都验不出来；因此这里一律用真库。
 * 造数标识取 {@code 8_100_000_000_000} 起的独立区间，清理不会波及别的进程写入的数据。
 */
public abstract class SiteDbTestSupport {

    protected static final long SITE_ID = 1001L;

    /**
     * 造数标识起点：每次 JVM 启动取一个不同的基址。
     *
     * <p>只用进程内自增会让两次运行的标识完全相同，上一次运行留下的遗漏行就会和这一次撞上。
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(
        8_100_000_000_000L + ProcessHandle.current().pid() * 1_000_000L
            + (System.nanoTime() & 0xFFFFFL));

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SkuSnapshotRepository snapshotRepository;

    @Autowired
    protected SkuSnapshotQueryService snapshotQueryService;

    @Autowired
    protected SkuSnapshotConsumer snapshotConsumer;

    @Autowired
    protected SiteChangeRequestService changeRequestService;

    @Autowired
    protected SiteAcceptanceService siteAcceptanceService;

    private final Set<String> archiveNos = new LinkedHashSet<>();
    private final Set<String> requestNos = new LinkedHashSet<>();
    private final Set<String> acceptNos = new LinkedHashSet<>();
    private final Set<Long> ownerStatusIds = new LinkedHashSet<>();

    @AfterEach
    void cleanUpCreatedRows() {
        for (Long ownerId : ownerStatusIds) {
            jdbc.update("delete from site_owner_status where owner_id = ?", ownerId);
        }
        ownerStatusIds.clear();
        for (String requestNo : requestNos) {
            jdbc.update("delete from site_archive_change_request where request_no = ?", requestNo);
        }
        // 受理号补一道兜底：申请行一落库就进列表，而 request_no 只能从受理结果里反查，
        // 用例在拿到它之前失败（或进程中断）就永远登记不上，那一行会留成下一次运行的脏数据。
        // 受理号由本次运行生成，按它删只命中本次受理自己写下的那条申请行，不扩大清理范围。
        for (String acceptNo : acceptNos) {
            jdbc.update("delete from site_archive_change_request where accept_no = ?", acceptNo);
        }
        for (String acceptNo : acceptNos) {
            jdbc.update("delete from site_archive_acceptance where accept_no = ?", acceptNo);
        }
        for (String requestNo : requestNos) {
            jdbc.update("delete from site_archive_acceptance where result_json like ?",
                "%" + requestNo + "%");
        }
        for (String archiveNo : archiveNos) {
            jdbc.update("delete from site_sku_snapshot where archive_no = ?", archiveNo);
        }
        archiveNos.clear();
        requestNos.clear();
        acceptNos.clear();
    }

    protected static long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    /** 造一个本仓缓存条目并登记清理。 */
    protected String applySnapshot(String ownerCode, String name, String status, long version,
                                   Instant dataTimestamp) {
        String archiveNo = "SKU" + nextId();
        archiveNos.add(archiveNo);
        snapshotRepository.apply(new SkuSnapshot(null, SITE_ID, nextId(), nextId(), archiveNo,
            ownerCode, name, "6900" + nextId(), "瓶", "常温", "饮料", "550ml", status, version,
            archiveNo + "#" + version, dataTimestamp, Instant.now()));
        return archiveNo;
    }

    protected void registerArchiveNo(String archiveNo) {
        archiveNos.add(archiveNo);
    }

    protected void registerRequestNo(String requestNo) {
        if (requestNo != null) {
            requestNos.add(requestNo);
        }
    }

    /**
     * 登记受理号：收尾时按它清理受理记录，以及本次受理写下的那条申请行。
     *
     * <p>受理号是运行内生成、落库前就已知的唯一键；{@code request_no} 只能从受理结果里反查，
     * 用例在拿到它之前失败时，靠受理号才收得干净。
     */
    protected void registerAcceptNo(String acceptNo) {
        if (acceptNo != null) {
            acceptNos.add(acceptNo);
        }
    }

    /**
     * 造一条货主状态副本并登记清理：{@code dataTimestamp} 由用例给出，
     * 用于覆盖「阈值内放行」与「超过阈值拒绝」两条判定。
     */
    protected long applyOwnerStatus(String status, long sourceVersion, Instant dataTimestamp) {
        long ownerId = nextId();
        ownerStatusIds.add(ownerId);
        // 编号取完整标识：{@code uk_site_owner_status_no} 要求唯一，截断成六位会在同一次运行内相撞。
        String ownerNo = ownerNoOf(ownerId);
        jdbc.update("""
            insert into site_owner_status (owner_id, owner_no, status, source_version, event_id,
                data_timestamp, updated_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """, ownerId, ownerNo, status, sourceVersion, ownerNo + "#" + sourceVersion,
            java.time.OffsetDateTime.ofInstant(dataTimestamp, java.time.ZoneOffset.UTC),
            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        return ownerId;
    }

    /** 造数用的货主编号：完整标识拼上 OW 前缀，满足 {@code ^OW[A-Z0-9]{6,30}$} 且同一次运行内不重。 */
    protected static String ownerNoOf(long ownerId) {
        return "OW" + ownerId;
    }

    /** 登记一个货主标识，用例结束删除其副本行（没有副本行时删除是空操作）。 */
    protected long registerOwnerStatus(long ownerId) {
        ownerStatusIds.add(ownerId);
        return ownerId;
    }

    /** 货主状态下行摘要：字段口径与 Hub 的 {@code OwnerStatusChangedEvent} 一致。 */
    protected String ownerStatusPayload(long ownerId, String ownerNo, String status, long version,
                                        Instant changedAt) {
        return """
            {"eventId":"%s#%d","ownerId":"%d","ownerNo":"%s","status":"%s","action":"DISABLE",
             "changedAt":"%s","version":%d}
            """.formatted(ownerNo, version, ownerId, ownerNo, status, changedAt, version);
    }

    /** 下行档案摘要：字段口径与 Hub 的 {@code ArchiveEventPayload} 一致。 */
    protected String archivePayload(String archiveId, String status, long version,
                                    Instant dataTimestamp) {
        return """
            {"archiveId":"%s","ownerId":"%d","ownerCode":"T-S-1","name":"矿泉水","barcode":"6901234567890",
             "baseUnit":"瓶","storageCondition":"常温","category":"饮料","spec":"550ml","status":"%s",
             "version":%d,"dataTimestamp":"%s","changedFields":["status"]}
            """.formatted(archiveId, nextId(), status, version, dataTimestamp);
    }
}
