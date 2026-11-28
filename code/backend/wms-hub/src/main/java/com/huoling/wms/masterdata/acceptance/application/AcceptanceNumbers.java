package com.huoling.wms.masterdata.acceptance.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 受理号生成：带时间前缀便于对账，重复请求回放时返回同一个受理号（幂等入口保证）。
 *
 * <p>为什么从档案模块上移到这里：受理号由受理机制生成，两个业务共用同一形态；
 * 留在档案模块会让受理机制反向依赖业务包。格式与上移前逐字一致（{@code ARC} 加时间戳加四位序号），
 * 既有受理单与新受理单在库里看不出差别。
 */
@Component
public class AcceptanceNumbers {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 受理号，形如 {@code ARC202611281030150001}。 */
    public String nextAcceptNo() {
        String stamp = OffsetDateTime.now(ZoneOffset.UTC).format(TIMESTAMP);
        return "ARC" + stamp + String.format("%04d", IdWorker.getId() % 10000);
    }
}
