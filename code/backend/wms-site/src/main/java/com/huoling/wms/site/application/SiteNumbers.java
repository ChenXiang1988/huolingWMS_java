package com.huoling.wms.site.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 单仓端的受理号与申请编号：雪花标识拼前缀，天然唯一且不随重试改变。
 *
 * <p>受理号带时间前缀便于对账；申请编号是单仓本地记录号，上行时由 Hub 另行分配权威编号，
 * 两者不混用（单仓不伪造 Hub 权威编号）。
 */
@Component
public class SiteNumbers {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 受理号，形如 {@code SAC202611281030150001}。 */
    public String nextAcceptNo() {
        String stamp = OffsetDateTime.now(ZoneOffset.UTC).format(TIMESTAMP);
        return "SAC" + stamp + String.format("%04d", IdWorker.getId() % 10000);
    }

    /** 本仓申请编号，形如 {@code SCR1234567890123456789}。 */
    public String nextRequestNo() {
        return "SCR" + IdWorker.getId();
    }
}
