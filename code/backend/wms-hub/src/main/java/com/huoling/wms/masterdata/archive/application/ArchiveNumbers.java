package com.huoling.wms.masterdata.archive.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Component;

/**
 * 档案系统编号与申请编号的生成。
 *
 * <p>系统编号与申请编号用雪花标识拼前缀，天然唯一且不随提交改变（`26` 3.2）。
 * 受理号已随受理机制上移到 {@code masterdata.acceptance} 的 {@code AcceptanceNumbers}：
 * 受理号由机制生成，两个业务共用同一形态，这里不再保留第二份实现。
 */
@Component
public class ArchiveNumbers {

    /** 档案系统编号，形如 {@code SKU1234567890123456789}。 */
    public String nextArchiveNo() {
        return "SKU" + IdWorker.getId();
    }

    /** 变更申请编号，形如 {@code CRQ1234567890123456789}。 */
    public String nextRequestNo() {
        return "CRQ" + IdWorker.getId();
    }
}
