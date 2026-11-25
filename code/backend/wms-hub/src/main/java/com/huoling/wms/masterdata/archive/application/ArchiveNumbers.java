package com.huoling.wms.masterdata.archive.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 档案系统编号、申请编号与受理号的生成。
 *
 * <p>系统编号与申请编号用雪花标识拼前缀，天然唯一且不随提交改变（`26` 3.2）；
 * 受理号带时间前缀便于对账，重复请求回放时返回同一个受理号（幂等入口保证）。
 */
@Component
public class ArchiveNumbers {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 档案系统编号，形如 {@code SKU1234567890123456789}。 */
    public String nextArchiveNo() {
        return "SKU" + IdWorker.getId();
    }

    /** 变更申请编号，形如 {@code CRQ1234567890123456789}。 */
    public String nextRequestNo() {
        return "CRQ" + IdWorker.getId();
    }

    /** 受理号，形如 {@code ARC202611281030150001}。 */
    public String nextAcceptNo() {
        String stamp = OffsetDateTime.now(ZoneOffset.UTC).format(TIMESTAMP);
        return "ARC" + stamp + String.format("%04d", IdWorker.getId() % 10000);
    }
}
