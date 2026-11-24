package com.huoling.wms.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 运营端启动入口：权威主数据与跨仓调度，不持有库存真值。
 */
@SpringBootApplication
public class HubApplication {

    public static void main(String[] args) {
        SpringApplication.run(HubApplication.class, args);
    }
}
