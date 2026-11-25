package com.huoling.wms.masterdata.archive.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 本地消息表的定时补偿开关：投递器与受理线程池以外的周期任务都靠它启用。
 *
 * <p>单独一个配置类而不是加到启动类：单仓端与接入层不需要 Hub 的下行投递，
 * 定时能力按单元开，不要在共用启动类上全局打开。
 */
@Configuration
@EnableScheduling
public class ArchiveOutboxConfig {
}
