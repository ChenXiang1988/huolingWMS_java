package com.huoling.wms.site.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 单仓端配置：本仓标识、令牌密钥、限流阈值、下行灰度开关与上行地址。
 *
 * <p>本仓标识只有一个来源（部署配置），令牌声明必须与它一致：令牌说自己是哪个仓，
 * 部署说这个进程是哪个仓，两者不一致即视为越权（契约「单仓端会话与权限」）。
 */
@ConfigurationProperties(prefix = "wms")
public class SiteProperties {

    private Site site = new Site();
    private Jwt jwt = new Jwt();
    private Archive archive = new Archive();
    private Owner owner = new Owner();

    public Owner getOwner() {
        return owner;
    }

    public void setOwner(Owner owner) {
        this.owner = owner;
    }

    public Site getSite() {
        return site;
    }

    public void setSite(Site site) {
        this.site = site;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Archive getArchive() {
        return archive;
    }

    public void setArchive(Archive archive) {
        this.archive = archive;
    }

    /** 本仓标识；零表示未配置，所有单仓接口一律拒绝（不猜默认仓）。 */
    public static class Site {
        private Long id = 0L;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }

    /** 令牌密钥：与 Hub 同一套，单仓端只验签不签发。 */
    public static class Jwt {
        private String secret;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    /** 档案域配置。 */
    public static class Archive {

        private long rateLimitPerMinute = 120;
        private Downlink downlink = new Downlink();
        private Uplink uplink = new Uplink();

        public long getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(long rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }

        public Downlink getDownlink() {
            return downlink;
        }

        public void setDownlink(Downlink downlink) {
            this.downlink = downlink;
        }

        public Uplink getUplink() {
            return uplink;
        }

        public void setUplink(Uplink uplink) {
            this.uplink = uplink;
        }
    }

    /** 下行消费：灰度开关关闭时不建监听容器，只展示已有缓存。 */
    public static class Downlink {
        private boolean enabled;
        private String queue = "wms.archive.sku.site";
        private String exchange = "wms.archive.events";
        private String routingKey = "wms.archive.sku";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getQueue() {
            return queue;
        }

        public void setQueue(String queue) {
            this.queue = queue;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getRoutingKey() {
            return routingKey;
        }

        public void setRoutingKey(String routingKey) {
            this.routingKey = routingKey;
        }
    }

    /**
     * 货主状态域配置（tasks 1.14）：下行消费与受理前闸门。
     *
     * <p>两个开关分开，对应 design「上线顺序」的第 ⑤ 步与第 ⑥ 步：
     * 先把下行队列绑上（只消费不拦截），确认副本覆盖率后再打开闸门。
     */
    public static class Owner {

        private Downlink downlink = new Downlink();
        private Blocking blocking = new Blocking();

        public Downlink getDownlink() {
            return downlink;
        }

        public void setDownlink(Downlink downlink) {
            this.downlink = downlink;
        }

        public Blocking getBlocking() {
            return blocking;
        }

        public void setBlocking(Blocking blocking) {
            this.blocking = blocking;
        }

        /** 货主状态下行消费：关闭时不建监听容器，本仓不连消息中间件。 */
        public static class Downlink {

            private boolean enabled;
            private String queue = "wms.owner.status.site";
            private String exchange = "wms.archive.events";
            private String routingKey = "wms.owner.status";
            private String deadLetterQueue = "wms.owner.status.dlq";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getQueue() {
                return queue;
            }

            public void setQueue(String queue) {
                this.queue = queue;
            }

            public String getExchange() {
                return exchange;
            }

            public void setExchange(String exchange) {
                this.exchange = exchange;
            }

            public String getRoutingKey() {
                return routingKey;
            }

            public void setRoutingKey(String routingKey) {
                this.routingKey = routingKey;
            }

            public String getDeadLetterQueue() {
                return deadLetterQueue;
            }

            public void setDeadLetterQueue(String deadLetterQueue) {
                this.deadLetterQueue = deadLetterQueue;
            }
        }

        /** 受理前闸门：默认关闭（副本覆盖率确认后再打开，回滚即关掉它）。 */
        public static class Blocking {

            private boolean enabled;
            private Duration staleThreshold = Duration.ofHours(1);

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            /** 副本数据时间超过它即视为长期失联，闸门拒绝放行（缺省 1 小时）。 */
            public Duration getStaleThreshold() {
                return staleThreshold;
            }

            public void setStaleThreshold(Duration staleThreshold) {
                this.staleThreshold = staleThreshold;
            }
        }
    }

    /** 上行：把变更申请写进 Hub 权威表的地址；未配置时申请留在本仓待上行。 */
    public static class Uplink {
        private String url;
        private String token;
        private long intervalMs = 30000L;
        private int batchSize = 20;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
