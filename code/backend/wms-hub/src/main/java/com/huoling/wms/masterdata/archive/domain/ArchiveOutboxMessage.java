package com.huoling.wms.masterdata.archive.domain;

import java.time.Instant;

/**
 * 本地消息表条目：审核通过与变更申请审核通过后写入，与业务同事务提交，之后异步下行。
 *
 * <p>事件标识为档案系统编号加版本号，消费方按它去重（design「幂等与异步事件」）；
 * 载荷是摘要而不是实体，含档案编号、版本号、数据时间戳与变更字段摘要。
 */
public record ArchiveOutboxMessage(
        Long id,
        String eventId,
        String eventType,
        String aggregateNo,
        long version,
        Long ownerId,
        String payloadJson,
        Instant createdAt) {

    public static final String TYPE_ARCHIVE_ACTIVATED = "SKU_ARCHIVE_ACTIVATED";
    public static final String TYPE_ARCHIVE_CHANGED = "SKU_ARCHIVE_CHANGED";
    /** 变更申请审核结果：与审核同事务落库，单仓据此更新本地申请状态。 */
    public static final String TYPE_CHANGE_REQUEST_REVIEWED = "SKU_CHANGE_REQUEST_REVIEWED";

    /** 事件标识：档案编号 + 版本号。 */
    public static String eventIdOf(String archiveNo, long version) {
        return archiveNo + "#" + version;
    }
}
