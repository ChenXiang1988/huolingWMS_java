package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchiveIdempotencyRepository;
import com.huoling.wms.masterdata.archive.domain.IdempotencyDecision;
import com.huoling.wms.masterdata.archive.domain.IdempotencyRecord;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 写接口幂等入口（契约「幂等约定」）：作用域为归属货主加接口加键，窗口二十四小时。
 *
 * <p>本类只做幂等判定与占位，不执行业务：业务由 {@link ArchiveAcceptanceService} 异步执行，
 * 因此「处理中」是常态而不是异常——首次受理与幂等记录同事务落库，执行完成后才把幂等记录标成成功。
 *
 * <p>四个出口由 {@link IdempotencyDecision} 判定：无记录或已过期即占位；
 * 同键同指纹且已成功回放（由受理记录给出首次受理号）；同键异体返回冲突；同键同指纹仍在处理返回可重试错误。
 */
@Service
public class ArchiveIdempotencyService {

    /** 契约：窗口不少于二十四小时。 */
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int KEY_MAX_LENGTH = 64;

    private final ArchiveIdempotencyRepository repository;

    public ArchiveIdempotencyService(ArchiveIdempotencyRepository repository) {
        this.repository = repository;
    }

    /** 幂等键必填，长度上限六十四（契约「幂等约定」）。 */
    public String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID, "缺少幂等键 Idempotency-Key");
        }
        if (key.length() > KEY_MAX_LENGTH) {
            throw new ArchiveException(ArchiveErrorCodes.PARAM_INVALID,
                "幂等键 Idempotency-Key 长度上限 " + KEY_MAX_LENGTH);
        }
        return key;
    }

    /** 请求指纹：覆盖接口与请求体，不含幂等键本身。 */
    public String fingerprint(String apiCode, String requestTarget, String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = apiCode + "\n" + (requestTarget == null ? "" : requestTarget)
                + "\n" + (requestBody == null ? "" : requestBody);
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按当前库中记录判定出口。 */
    public IdempotencyDecision.Outcome decide(String scopeKey, String apiCode, String key,
                                              String fingerprint, Instant now) {
        IdempotencyRecord record = repository.find(scopeKey, apiCode, key);
        return IdempotencyDecision.decide(record, fingerprint, now == null ? Instant.now() : now);
    }

    /**
     * 占位：插入处理中的记录。
     *
     * @return {@code false} 表示唯一键已被并发请求占用，调用方回到读取分支重新判定
     */
    public boolean reserve(String scopeKey, String apiCode, String key, String fingerprint, Instant now) {
        Instant createdAt = now == null ? Instant.now() : now;
        return repository.reserve(scopeKey, apiCode, key, fingerprint, createdAt, createdAt.plus(WINDOW));
    }

    /** 执行结束（成功或业务失败都算「首次受理已有结果」）：同键重复请求从此回放首次受理号。 */
    public void markAccepted(String scopeKey, String apiCode, String key, String resultJson) {
        repository.markSucceeded(scopeKey, apiCode, key, resultJson);
    }

    /** 回放首次结果：同键同指纹时把首次写下的结果原样取回。 */
    public String replay(String scopeKey, String apiCode, String key) {
        IdempotencyRecord record = repository.find(scopeKey, apiCode, key);
        return record == null ? null : record.resultJson();
    }

    /** 处理中的退避间隔：按窗口剩余时间给出，最少一秒。 */
    public long retryAfterMs(String scopeKey, String apiCode, String key) {
        IdempotencyRecord record = repository.find(scopeKey, apiCode, key);
        if (record == null || record.expiresAt() == null) {
            return 1000L;
        }
        long remaining = record.expiresAt().toEpochMilli() - Instant.now().toEpochMilli();
        return Math.max(1000L, remaining);
    }
}
