package com.huoling.wms.site.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

/**
 * 单仓端基础设施装配：Mapper 扫描、配置绑定、定时补偿与下行监听。
 *
 * <p>定时任务与监听容器都按单元开：单仓端的定时任务是「变更申请上行」与「退避重试」，
 * 与 Hub 的下行投递互不共享。
 */
@Configuration
@MapperScan("com.huoling.wms.site.infrastructure.persistence")
@EnableConfigurationProperties(SiteProperties.class)
@EnableScheduling
@EnableRabbit
public class SiteInfrastructureConfig {
}
