package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.RoleRepository;
import com.huoling.wms.masterdata.identity.domain.RoleScopeRepository;
import com.huoling.wms.masterdata.limit.ApiRateLimiter;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.support.BrokenMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 越权拒绝与指标的关系（复审提出的受理侧指标兜底）。
 *
 * <p>{@code OwnerAccessGuard} 在抛出 {@code FORBIDDEN_OWNER} 之前先记一次
 * {@code owner_scope_denied_total}。计数在拒绝之前调用，Micrometer 抛错会把预期的 403
 * 顶成 500：越权拒绝被记成系统故障，安全事件与告警口径同时失真。
 * 这里用「注册即抛错」的注册表打这条路径，断言拒绝原因原样透出。
 */
class OwnerAccessGuardTest {

    @Test
    @DisplayName("指标抛错：端不符仍是 403 FORBIDDEN_OWNER，不变 500")
    void 指标抛错不改越权拒绝() {
        OwnerAccessGuard guard = new OwnerAccessGuard(mock(RoleRepository.class),
            mock(RoleScopeRepository.class), mock(ApiRateLimiter.class),
            new OwnerMetrics(new BrokenMeterRegistry()));

        assertThatThrownBy(() -> guard.requireHubScope(9L, ClientType.OWNER))
            .as("端标识不是 HUB 一律拒绝，计数成败不改变这条判定")
            .isInstanceOf(OwnerContractException.class)
            .extracting(e -> ((OwnerContractException) e).code())
            .isEqualTo(OwnerErrorCodes.FORBIDDEN_OWNER);
    }
}
