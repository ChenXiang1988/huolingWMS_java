package com.huoling.wms.masterdata.archive.domain;

import java.time.Instant;

/**
 * 变更申请审核结果的下行摘要（design「事件与回调」：变更申请审核结果同事件通知单仓）。
 *
 * <p>只带申请编号、档案编号、结果状态与写回后的档案版本号，不带申请原因与操作人；
 * 单仓据 {@code requestNo} 更新本地申请状态，据 {@code archiveVersion} 判断缓存是否落后。
 */
public record ChangeRequestEventPayload(
        String requestNo,
        String archiveId,
        String status,
        String reviewReason,
        Long archiveVersion,
        Long siteId,
        Instant dataTimestamp) {
}
