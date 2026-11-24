package com.huoling.wms.site;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 单仓端启动入口：库存真值、库位、任务与收发存；启动时校验仓库绑定。
 */
@SpringBootApplication
public class SiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(SiteApplication.class, args);
    }
}
