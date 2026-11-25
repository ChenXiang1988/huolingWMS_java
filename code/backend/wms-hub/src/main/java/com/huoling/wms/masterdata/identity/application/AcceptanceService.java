package com.huoling.wms.masterdata.identity.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.identity.domain.IdentityErrorCodes;
import com.huoling.wms.masterdata.identity.domain.IdentityException;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AcceptanceMapper;
import com.huoling.wms.masterdata.identity.infrastructure.persistence.AcceptancePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 受理环：写入先落受理单并返回受理号，业务由线程池执行（D20）。
 *
 * <p>幂等口径（契约「幂等约定」）：作用域为操作账号 + 接口 + 请求指纹。服务端先按
 * （账号、接口、幂等键）索引受理单，命中后再比对请求指纹：指纹一致返回首次受理号，
 * 指纹不同返回 409 {@code COMMON_9005}，且不覆盖首次受理单、不新建受理单。
 */
@Service
public class AcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceService.class);
    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int KEY_MAX_LENGTH = 64;

    private final AcceptanceMapper acceptanceMapper;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate transactionTemplate;

    public AcceptanceService(AcceptanceMapper acceptanceMapper,
                             ThreadPoolTaskExecutor acceptanceExecutor,
                             TransactionTemplate transactionTemplate) {
        this.acceptanceMapper = acceptanceMapper;
        this.executor = acceptanceExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * @param apiCode        接口编号，等价于「方法与路径」，构成指纹的第一段
     * @param requestTarget  路径参数的实际取值，构成指纹的第二段（如 {@code /hub/api/roles/1001/scopes}）
     * @param idempotencyKey 请求头 Idempotency-Key，只做去重索引，不参与指纹
     */
    public String submit(Long accountId, String apiCode, String requestTarget,
                         String idempotencyKey, String requestBody, Runnable action) {
        String key = requireKey(idempotencyKey);
        String fingerprint = fingerprint(apiCode, requestTarget, requestBody);

        AcceptancePO existing = acceptanceMapper.findByIdempotencyKey(accountId, apiCode, key);
        if (existing != null) {
            return replay(existing, fingerprint);
        }

        AcceptancePO po = new AcceptancePO();
        po.setId(IdWorker.getId());
        po.setAcceptNo(nextAcceptNo());
        po.setApiCode(apiCode);
        po.setAccountId(accountId);
        po.setIdempotencyKey(key);
        po.setFingerprint(fingerprint);
        po.setStatus("PENDING");
        po.setCreatedAt(OffsetDateTime.now());
        try {
            acceptanceMapper.insert(po);
        } catch (DataIntegrityViolationException e) {
            // 并发同键：唯一索引只放行一张受理单，落后的一方回到首次受理单上判定。
            AcceptancePO winner = acceptanceMapper.findByIdempotencyKey(accountId, apiCode, key);
            if (winner == null) {
                log.warn("受理单写入失败，键 {}", key, e);
                throw new IdentityException(IdentityErrorCodes.COMMON_UNAVAILABLE, "受理单写入失败，请稍后重试");
            }
            return replay(winner, fingerprint);
        }

        executor.execute(() -> run(po, action));
        return po.getAcceptNo();
    }

    private String replay(AcceptancePO existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new IdentityException(IdentityErrorCodes.COMMON_IDEMPOTENCY_CONFLICT,
                "同一 Idempotency-Key 已受理过，本次请求体与首次不同");
        }
        return existing.getAcceptNo();
    }

    private String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST, "缺少幂等键 Idempotency-Key");
        }
        if (idempotencyKey.length() > KEY_MAX_LENGTH) {
            throw new IdentityException(IdentityErrorCodes.COMMON_BAD_REQUEST,
                "幂等键 Idempotency-Key 长度上限 " + KEY_MAX_LENGTH);
        }
        return idempotencyKey;
    }

    private void run(AcceptancePO po, Runnable action) {
        po.setStatus("RUNNING");
        acceptanceMapper.updateById(po);
        try {
            transactionTemplate.executeWithoutResult(status -> action.run());
            po.setStatus("SUCCEEDED");
        } catch (IdentityException e) {
            po.setStatus("FAILED");
            po.setErrorCode(e.code());
        } catch (RuntimeException e) {
            log.warn("受理单 {} 执行失败", po.getAcceptNo(), e);
            po.setStatus("FAILED");
            po.setErrorCode(IdentityErrorCodes.COMMON_UNAVAILABLE);
        } finally {
            po.setFinishedAt(OffsetDateTime.now());
            acceptanceMapper.updateById(po);
        }
    }

    public AcceptancePO findByNo(Long accountId, String acceptNo) {
        AcceptancePO po = acceptanceMapper.findByAcceptNo(acceptNo);
        if (po == null || !po.getAccountId().equals(accountId)) {
            throw new IdentityException(IdentityErrorCodes.ACCOUNT_NOT_FOUND, "受理单不存在");
        }
        return po;
    }

    private String nextAcceptNo() {
        return "HUB" + OffsetDateTime.now().format(NO_FORMAT)
            + String.format("%04d", IdWorker.getId() % 10000);
    }

    /**
     * 请求指纹：覆盖接口（方法与路径）、路径参数与请求体，不含幂等键本身（契约幂等约定第 2 条）。
     */
    private String fingerprint(String apiCode, String requestTarget, String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = apiCode + "\n" + (requestTarget == null ? "" : requestTarget)
                + "\n" + (requestBody == null ? "" : requestBody);
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
