package com.huoling.wms.masterdata.identity.api;

import com.huoling.wms.masterdata.identity.application.AcceptanceService;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AcceptancePO;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 受理结果查询（API-018）。只允许查本人提交的受理单。
 */
@RestController
@RequestMapping("/hub/api/acceptances")
public class AcceptanceController {

    private final AcceptanceService acceptanceService;
    private final JwtTokenService tokenService;

    public AcceptanceController(AcceptanceService acceptanceService, JwtTokenService tokenService) {
        this.acceptanceService = acceptanceService;
        this.tokenService = tokenService;
    }

    @GetMapping("/{acceptNo}")
    public ApiResponse<AcceptanceView> detail(
            @PathVariable String acceptNo,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CurrentUser user = CurrentUser.from(tokenService, authorization);
        AcceptancePO po = acceptanceService.findByNo(user.accountId(), acceptNo);
        return ApiResponse.ok(new AcceptanceView(po.getAcceptNo(), po.getStatus(), po.getResultJson(),
            po.getErrorCode(), po.getCreatedAt(), po.getFinishedAt()), traceId());
    }

    private String traceId() {
        return "tr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public record AcceptanceView(String acceptNo, String status, String result,
                                 String errorCode, java.time.OffsetDateTime createdAt,
                                 java.time.OffsetDateTime finishedAt) {
    }
}
