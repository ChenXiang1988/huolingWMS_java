package com.huoling.wms.site.application;

import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.SiteChangeRequest;
import com.huoling.wms.site.domain.SiteChangeRequestRepository;
import com.huoling.wms.site.infrastructure.HubUplinkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 变更申请上行投递器（本地消息表 + 定时补偿的 Site 侧实现，tasks 1.10、1.12）。
 *
 * <p>按固定间隔扫描「待上行或上次失败」的申请，逐条发往 Hub；失败累计重试次数并按指数退避，
 * 退避在 SQL 里算，多副本不会把退避算错。上行成功只改上行进度，不改业务状态——
 * 业务状态只由 Hub 的下行审核结果推进。
 *
 * <p>上行地址未配置时整轮跳过并只记一次告警：申请保持「待上行」，不伪造成已写入权威表。
 */
@Component
public class SiteUplinkDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SiteUplinkDispatcher.class);

    private final SiteChangeRequestRepository changeRequests;
    private final HubUplinkClient uplinkClient;
    private final SiteProperties properties;

    public SiteUplinkDispatcher(SiteChangeRequestRepository changeRequests,
                                HubUplinkClient uplinkClient,
                                SiteProperties properties) {
        this.changeRequests = changeRequests;
        this.uplinkClient = uplinkClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${wms.archive.uplink.interval-ms:30000}")
    public void dispatch() {
        int uploaded = dispatchOnce();
        if (uploaded > 0) {
            log.info("变更申请上行完成，本次成功 {} 条", uploaded);
        }
    }

    /**
     * 扫一轮待上行申请。
     *
     * @return 本轮上行成功的条数
     */
    public int dispatchOnce() {
        if (!uplinkClient.configured()) {
            log.debug("未配置 wms.archive.uplink.url，跳过上行；申请留在本仓待上行");
            return 0;
        }
        int batch = properties.getArchive().getUplink().getBatchSize();
        List<SiteChangeRequest> pending = changeRequests.findUploadable(batch <= 0 ? 20 : batch,
            Instant.now());
        int uploaded = 0;
        for (SiteChangeRequest request : pending) {
            try {
                uplinkClient.upload(request);
                changeRequests.markUploaded(request.requestNo(), Instant.now());
                uploaded++;
            } catch (RuntimeException e) {
                log.warn("变更申请 {} 上行失败，按退避重试：{}", request.requestNo(), e.getMessage());
                changeRequests.markUploadFailed(request.requestNo(), e.getMessage(), Instant.now());
            }
        }
        return uploaded;
    }
}
