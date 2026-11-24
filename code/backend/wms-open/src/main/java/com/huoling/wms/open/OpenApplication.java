package com.huoling.wms.open;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 公网接入层启动入口：认证、限流、货主门户与开放接口；不碰库存。
 */
@SpringBootApplication
public class OpenApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenApplication.class, args);
    }
}
