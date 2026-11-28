package com.huoling.wms.site.application;

import com.huoling.wms.site.support.SiteDbTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 闸门开关关闭时的行为（tasks 1.14；design「开关关闭（{@code wms.owner.blocking.enabled=false}）→ 全部放行」）。
 *
 * <p>默认值就是关闭：**不配置任何属性**跑本用例，证明确实「默认不受影响」——
 * 副本可以是停用的、可以没有记录、可以陈旧，一律放行。这也是上线顺序里
 * 「先只消费不拦截」与回滚路径的依据。
 */
@SpringBootTest(classes = com.huoling.wms.site.SiteApplication.class,
    properties = {"wms.site.id=1001"})
class OwnerStatusGateDisabledIT extends SiteDbTestSupport {

    @Autowired
    private OwnerStatusGate gate;

    @Test
    @DisplayName("开关关闭：停用、无记录、陈旧三种情形一律放行")
    void 开关关闭全放行() {
        long disabled = applyOwnerStatus("DISABLED", 2, Instant.now().minus(Duration.ofHours(5)));
        long missing = registerOwnerStatus(nextId());
        long stale = applyOwnerStatus("ACTIVE", 3, Instant.now().minus(Duration.ofHours(9)));

        assertThatCode(() -> gate.assertCanAcceptNewBusiness(disabled)).doesNotThrowAnyException();
        assertThatCode(() -> gate.assertCanAcceptNewBusiness(missing)).doesNotThrowAnyException();
        assertThatCode(() -> gate.assertCanAcceptNewBusiness(stale)).doesNotThrowAnyException();
    }
}
