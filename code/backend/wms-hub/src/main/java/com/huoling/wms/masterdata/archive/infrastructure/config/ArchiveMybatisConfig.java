package com.huoling.wms.masterdata.archive.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 商品档案模块的 Mapper 扫描。与身份模块的扫描分开声明，两边包路径互不覆盖；
 * 分页与乐观锁插件由身份模块的 {@code MybatisConfig} 统一提供，这里不重复定义。
 */
@Configuration
@MapperScan("com.huoling.wms.masterdata.archive.infrastructure.persistence")
public class ArchiveMybatisConfig {
}
