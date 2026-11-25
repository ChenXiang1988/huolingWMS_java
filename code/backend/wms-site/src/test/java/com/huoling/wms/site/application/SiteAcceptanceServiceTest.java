package com.huoling.wms.site.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.site.domain.AcceptanceState;
import com.huoling.wms.site.domain.SiteAcceptance;
import com.huoling.wms.site.domain.SiteAcceptanceRepository;
import com.huoling.wms.site.domain.SiteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 单仓受理（tasks 1.10）：重复提交只产生一条受理记录，失败保留错误码。 */
class SiteAcceptanceServiceTest {

    private static final long SITE_ID = 1001L;
    private static final String API = "API-011";

    private FakeAcceptanceRepository acceptances;
    private SiteAcceptanceService service;

    @BeforeEach
    void setUp() {
        acceptances = new FakeAcceptanceRepository();
        service = new SiteAcceptanceService(acceptances, new SiteNumbers(),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    @DisplayName("首次受理返回受理号与待审核快照，执行成功后可按受理号查到结果")
    void 首次受理成功() {
        SiteAcceptanceService.Ticket ticket = service.submit(SITE_ID, API, "k-1", "SKU-1",
            "body", "PENDING", acceptNo -> new Created("SCR-1"));

        assertThat(ticket.acceptNo()).startsWith("SAC");
        assertThat(ticket.status()).isEqualTo("PENDING");
        SiteAcceptanceService.View view = service.find(SITE_ID, ticket.acceptNo());
        assertThat(view.acceptStatus()).isEqualTo(AcceptanceState.SUCCEEDED.name());
        assertThat(view.result().get("requestNo").asText()).isEqualTo("SCR-1");
    }

    @Test
    @DisplayName("重复提交只产生一条受理记录，回放首次受理号且不重复执行")
    void 重复提交回放() {
        AtomicInteger executions = new AtomicInteger();
        SiteAcceptanceService.Ticket first = service.submit(SITE_ID, API, "k-2", "SKU-1", "body",
            "PENDING", acceptNo -> {
                executions.incrementAndGet();
                return new Created("SCR-2");
            });
        SiteAcceptanceService.Ticket second = service.submit(SITE_ID, API, "k-2", "SKU-1", "body",
            "PENDING", acceptNo -> {
                executions.incrementAndGet();
                return new Created("SCR-2");
            });

        assertThat(second.acceptNo()).isEqualTo(first.acceptNo());
        assertThat(executions.get()).isEqualTo(1);
        assertThat(acceptances.rows).hasSize(1);
    }

    @Test
    @DisplayName("同键异体按 IDEMPOTENT_KEY_CONFLICT 拒绝")
    void 同键异体拒绝() {
        service.submit(SITE_ID, API, "k-3", "SKU-1", "body-a", "PENDING", acceptNo -> new Created("SCR-3"));

        assertThatThrownBy(() -> service.submit(SITE_ID, API, "k-3", "SKU-1", "body-b", "PENDING",
            acceptNo -> new Created("SCR-3")))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.IDEMPOTENT_KEY_CONFLICT);
    }

    @Test
    @DisplayName("业务失败：受理记录为失败并带契约错误码，重复请求仍回放同一受理号")
    void 失败带错误码() {
        SiteAcceptanceService.Ticket ticket = service.submit(SITE_ID, API, "k-4", "SKU-1", "body",
            "PENDING", acceptNo -> {
                throw new SiteException(ArchiveContractCodes.STATE_INVALID, "已有待审核申请");
            });

        SiteAcceptanceService.View view = service.find(SITE_ID, ticket.acceptNo());
        assertThat(view.acceptStatus()).isEqualTo(AcceptanceState.FAILED.name());
        assertThat(view.errorCode()).isEqualTo(ArchiveContractCodes.STATE_INVALID);

        SiteAcceptanceService.Ticket replay = service.submit(SITE_ID, API, "k-4", "SKU-1", "body",
            "PENDING", acceptNo -> new Created("SCR-4"));
        assertThat(replay.acceptNo()).isEqualTo(ticket.acceptNo());
        assertThat(acceptances.rows).hasSize(1);
    }

    @Test
    @DisplayName("跨仓查受理号按 NOT_FOUND_ARCHIVE 拒绝")
    void 跨仓查询拒绝() {
        SiteAcceptanceService.Ticket ticket = service.submit(SITE_ID, API, "k-5", "SKU-1", "body",
            "PENDING", acceptNo -> new Created("SCR-5"));

        assertThatThrownBy(() -> service.find(2002L, ticket.acceptNo()))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.NOT_FOUND_ARCHIVE);
    }

    private record Created(String requestNo) {
    }

    private static final class FakeAcceptanceRepository implements SiteAcceptanceRepository {

        private final List<SiteAcceptance> rows = new ArrayList<>();

        @Override
        public SiteAcceptance findByIdempotency(Long siteId, String apiCode, String idempotencyKey) {
            return rows.stream()
                .filter(row -> row.siteId().equals(siteId) && row.apiCode().equals(apiCode)
                    && row.idempotencyKey().equals(idempotencyKey))
                .findFirst().orElse(null);
        }

        @Override
        public SiteAcceptance findByAcceptNo(Long siteId, String acceptNo) {
            return rows.stream()
                .filter(row -> row.siteId().equals(siteId) && row.acceptNo().equals(acceptNo))
                .findFirst().orElse(null);
        }

        @Override
        public void insert(SiteAcceptance acceptance) {
            rows.add(acceptance);
        }

        @Override
        public void markSucceeded(String acceptNo, String resultJson, Instant now) {
            replace(byNo(acceptNo).succeeded(resultJson, now));
        }

        @Override
        public void markFailed(String acceptNo, String errorCode, String errorMessage, Instant now) {
            replace(byNo(acceptNo).failed(errorCode, errorMessage, now));
        }

        private SiteAcceptance byNo(String acceptNo) {
            return rows.stream().filter(row -> row.acceptNo().equals(acceptNo)).findFirst().orElseThrow();
        }

        private void replace(SiteAcceptance acceptance) {
            rows.replaceAll(row -> row.acceptNo().equals(acceptance.acceptNo()) ? acceptance : row);
        }
    }
}
