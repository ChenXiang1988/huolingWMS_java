package com.huoling.wms.masterdata.identity.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需权限点。白名单之外的接口缺此注解时应用启动失败（19）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 权限点编码，形如 hub.account.read。 */
    String value();
}
