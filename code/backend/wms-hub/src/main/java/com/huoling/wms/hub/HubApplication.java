package com.huoling.wms.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 运营端启动入口：权威主数据与跨仓调度，不持有库存真值。
 */
@SpringBootApplication(scanBasePackages = "com.huoling.wms")
@ConfigurationPropertiesScan("com.huoling.wms")
public class HubApplication {

    public static void main(String[] args) {
        SpringApplication.run(HubApplication.class, args);
    }
}
