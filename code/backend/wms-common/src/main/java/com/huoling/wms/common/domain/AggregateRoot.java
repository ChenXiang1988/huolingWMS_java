package com.huoling.wms.common.domain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记聚合根。构建期由 AR-04、AR-05、AR-06 校验：不暴露 setter、必须有行为、状态字段私有且为枚举。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AggregateRoot {
}
