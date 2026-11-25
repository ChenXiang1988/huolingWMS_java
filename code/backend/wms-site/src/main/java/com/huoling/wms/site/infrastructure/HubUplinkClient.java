package com.huoling.wms.site.infrastructure;

import com.huoling.wms.site.config.SiteProperties;
import com.huoling.wms.site.domain.SiteChangeRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 变更申请上行到 Hub（契约「单仓端会话与权限」：变更申请经 Site 单元上行后写入 Hub 权威表）。
 *
 * <p><b>落点说明（契约缺口已登记）</b>：上行通道没有接口编号，契约章也没有给路径与鉴权方式；
 * 这里按部署配置的完整地址（{@code wms.archive.uplink.url}）与 bearer 凭证外发，
 * 请求体字段沿用契约字段名（{@code archiveId}、{@code changeField}、{@code targetValue}、{@code reason}），
 * 幂等键取本仓申请编号。地址未配置时投递器不动作，申请留在本仓待上行，不伪造成已上行。
 */
@Component
public class HubUplinkClient {

    private final SiteProperties properties;
    private final RestClient restClient;

    public HubUplinkClient(SiteProperties properties) {
        this.properties = properties;
        // 上行预算小于接口总预算（契约「鲁棒性约定」）：连 500ms、读 1200ms，超时快速失败交给退避重试。
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(1200));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 是否已配置上行地址；未配置时投递器跳过本轮。 */
    public boolean configured() {
        String url = properties.getArchive().getUplink().getUrl();
        return url != null && !url.isBlank();
    }

    /**
     * 上行一条申请。
     *
     * @throws RuntimeException 上行失败；由投递器记失败并按退避重试
     */
    public void upload(SiteChangeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("archiveId", request.archiveId());
        body.put("changeField", request.changeField());
        body.put("targetValue", request.targetValue());
        body.put("reason", request.reason());
        restClient.post()
            .uri(properties.getArchive().getUplink().getUrl())
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", request.requestNo())
            .headers(headers -> {
                String token = properties.getArchive().getUplink().getToken();
                if (token != null && !token.isBlank()) {
                    headers.setBearerAuth(token);
                }
            })
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }
}
