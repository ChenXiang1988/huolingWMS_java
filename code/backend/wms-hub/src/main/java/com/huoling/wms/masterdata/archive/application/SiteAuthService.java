package com.huoling.wms.masterdata.archive.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.acceptance.application.IdempotencyService;
import com.huoling.wms.masterdata.acceptance.domain.IdempotencyDecision;
import com.huoling.wms.masterdata.archive.infrastructure.SiteRefreshTokenStore;
import com.huoling.wms.masterdata.archive.infrastructure.config.SiteRegistryProperties;
import com.huoling.wms.masterdata.identity.infrastructure.JwtTokenService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * 单仓端令牌换取与续期（API-018、API-019）。
 *
 * <p>契约「单仓端会话与权限」：令牌由 Hub 签发，声明含唯一 {@code siteId} 与端标识 {@code SITE}，
 * 并带权限副本。本服务只做三件事：校验站点凭证与站点声明、签发令牌、按幂等键回放首次令牌。
 *
 * <p>多仓声明的判定在入口：同一个站点编码登记了多条，或单条登记了多个本仓标识，
 * 一律 {@code FORBIDDEN_SITE}，不签发令牌、不返回任何档案数据（specs product-archive-access）。
 *
 * <p>续期只换令牌不换站：刷新凭证里带着本仓标识，续期时按它重新解析权限副本；
 * 站点登记已下线时同样 {@code FORBIDDEN_SITE}。
 */
@Service
public class SiteAuthService {

    private static final String API_LOGIN = "API-018";
    private static final String API_REFRESH = "API-019";
    private static final String LOGIN_SCOPE = "SITE-AUTH:";
    private static final String REFRESH_SCOPE = "SITE-AUTH-REFRESH:";

    private final SiteRegistryProperties registry;
    private final SiteRefreshTokenStore refreshTokenStore;
    private final JwtTokenService tokenService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public SiteAuthService(SiteRegistryProperties registry,
                           SiteRefreshTokenStore refreshTokenStore,
                           JwtTokenService tokenService,
                           IdempotencyService idempotency,
                           ObjectMapper objectMapper) {
        this.registry = registry;
        this.refreshTokenStore = refreshTokenStore;
        this.tokenService = tokenService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * API-018：站点凭证换令牌。
     *
     * @param clientRequestId 客户端请求号，同时作为 {@code Idempotency-Key}；重复请求返回首次令牌
     */
    public Issued login(String siteCode, String credential, String clientRequestId) {
        String key = idempotency.requireKey(clientRequestId);
        String scope = LOGIN_SCOPE + (siteCode == null ? "" : siteCode);
        String fingerprint = idempotency.fingerprint(API_LOGIN, siteCode, credential);
        return once(scope, API_LOGIN, key, fingerprint, () -> issue(requireSite(siteCode, credential)));
    }

    /**
     * API-019：刷新凭证换新令牌，端标识必须为 {@code SITE}。
     *
     * <p>刷新凭证按契约字段名 {@code refreshCredential} 的语义收：入口可能来自请求体显式回传，
     * 也可能来自 httpOnly cookie，两条通道在这里合流，缺一即 {@code PARAM_INVALID}。
     *
     * <p>作用域取刷新凭证的摘要而不是站点：续期前还没有可信的站点声明，
     * 用凭证摘要保证「同一凭证加接口加键」的作用域，比全站共用一个键更严。
     */
    public Issued refresh(String refreshCredential, String clientRequestId) {
        String key = idempotency.requireKey(clientRequestId);
        if (refreshCredential == null || refreshCredential.isBlank()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "缺少刷新凭证");
        }
        String scope = REFRESH_SCOPE + digest(refreshCredential);
        String fingerprint = idempotency.fingerprint(API_REFRESH, null, refreshCredential);
        return once(scope, API_REFRESH, key, fingerprint, () -> {
            SiteRefreshTokenStore.RefreshSubject subject = refreshTokenStore.consume(refreshCredential)
                .orElseThrow(() -> new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE,
                    "刷新凭证无效或已使用，请重新换取站点令牌"));
            return issue(bySiteId(subject.siteId()));
        });
    }

    /**
     * API-018、API-019 响应：令牌、唯一本仓标识、权限副本与有效期。
     *
     * <p>{@code refreshToken} 不进响应体：契约响应只列「令牌、siteId、功能权限列表与有效期」，
     * 刷新凭证由接口以 httpOnly Cookie 下发（与运营端登录同一套做法），供 API-019 使用。
     */
    public record Issued(String accessToken, String siteId, List<String> permissions, long expiresIn,
                         String refreshToken) {
    }

    private Issued issue(Site site) {
        String accessToken = tokenService.issueSite(site.accountId(), site.siteId(), site.permissions());
        String refreshToken = refreshTokenStore.issue(site.accountId(), site.siteId());
        return new Issued(accessToken, String.valueOf(site.siteId()), site.permissions(),
            tokenService.accessTokenSeconds(), refreshToken);
    }

    private Site requireSite(String siteCode, String credential) {
        if (siteCode == null || siteCode.isBlank() || credential == null || credential.isBlank()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "缺少 siteCode 或站点凭证");
        }
        List<SiteRegistryProperties.Site> sameCode = registry.getSites().stream()
            .filter(site -> siteCode.equals(site.getCode()))
            .toList();
        if (sameCode.isEmpty()) {
            // 站点不存在与凭证错误同一响应：不泄露站点是否已登记。
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE, "站点凭证无效");
        }
        if (sameCode.size() > 1) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE,
                "同一站点声明了多个本仓，单仓端令牌必须唯一本仓");
        }
        SiteRegistryProperties.Site site = sameCode.get(0);
        if (site.getSiteIds() == null || site.getSiteIds().size() != 1) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE,
                "站点声明了 " + (site.getSiteIds() == null ? 0 : site.getSiteIds().size())
                    + " 个本仓，单仓端令牌必须唯一本仓");
        }
        if (!credential.equals(site.getCredential())) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE, "站点凭证无效");
        }
        return toSite(site);
    }

    private Site bySiteId(Long siteId) {
        List<SiteRegistryProperties.Site> matched = registry.getSites().stream()
            .filter(site -> site.getSiteIds() != null && site.getSiteIds().size() == 1
                && String.valueOf(siteId).equals(site.getSiteIds().get(0)))
            .toList();
        if (matched.size() != 1) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE,
                "站点登记已变化，请重新换取站点令牌");
        }
        return toSite(matched.get(0));
    }

    private Site toSite(SiteRegistryProperties.Site site) {
        Long siteId = parseId(site.getSiteIds().get(0));
        Long accountId = site.getAccountId() == null ? 0L : site.getAccountId();
        List<String> permissions = site.getPermissions() == null
            ? List.of() : List.copyOf(site.getPermissions());
        return new Site(siteId, accountId, permissions);
    }

    private Long parseId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "站点登记的本仓标识必须为十进制数字，收到 " + raw);
        }
    }

    private Issued once(String scopeKey, String apiCode, String key, String fingerprint,
                        Supplier<Issued> action) {
        Instant now = Instant.now();
        for (int attempt = 0; attempt < 2; attempt++) {
            IdempotencyDecision.Outcome outcome = idempotency.decide(scopeKey, apiCode, key,
                fingerprint, now);
            switch (outcome) {
                case REPLAY -> {
                    return read(scopeKey, apiCode, key);
                }
                case CONFLICT -> throw new ArchiveException(ArchiveErrorCodes.IDEMPOTENT_KEY_CONFLICT,
                    "同一 Idempotency-Key 已受理过，本次请求体与首次不同");
                case IN_PROGRESS -> throw new ArchiveException(ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS,
                    "同一 Idempotency-Key 的请求正在处理中，请稍后重试",
                    idempotency.retryAfterMs(scopeKey, apiCode, key));
                case PROCEED -> {
                    if (!idempotency.reserve(scopeKey, apiCode, key, fingerprint, now)) {
                        continue;
                    }
                    Issued issued = action.get();
                    idempotency.markAccepted(scopeKey, apiCode, key, write(issued));
                    return issued;
                }
                default -> throw new ArchiveException(ArchiveErrorCodes.INTERNAL_ERROR, "幂等判定异常");
            }
        }
        throw new ArchiveException(ArchiveErrorCodes.IDEMPOTENT_IN_PROGRESS,
            "同一 Idempotency-Key 的请求正在处理中，请稍后重试",
            idempotency.retryAfterMs(scopeKey, apiCode, key));
    }

    private Issued read(String scopeKey, String apiCode, String key) {
        String json = idempotency.replay(scopeKey, apiCode, key);
        if (json == null || json.isBlank()) {
            throw new ArchiveException(ArchiveErrorCodes.INTERNAL_ERROR, "首次令牌缺失，请重新换取");
        }
        try {
            return objectMapper.readValue(json, Issued.class);
        } catch (JsonProcessingException e) {
            throw new ArchiveException(ArchiveErrorCodes.INTERNAL_ERROR, "首次令牌解析失败，请重新换取");
        }
    }

    private String write(Issued issued) {
        try {
            return objectMapper.writeValueAsString(issued);
        } catch (JsonProcessingException e) {
            throw new ArchiveException(ArchiveErrorCodes.INTERNAL_ERROR, "令牌响应序列化失败");
        }
    }

    private String digest(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 解析后的站点：唯一本仓、令牌主体与权限副本。 */
    private record Site(Long siteId, Long accountId, List<String> permissions) {
    }
}
