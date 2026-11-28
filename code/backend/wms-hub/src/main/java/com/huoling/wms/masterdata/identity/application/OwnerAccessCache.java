package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.identity.domain.OwnerBindingDirectory;
import com.huoling.wms.masterdata.identity.domain.OwnerSessionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 「账号 + 货主状态」的可用性缓存（design「账号与登录」的「已签发令牌下一次请求被拒」）。
 *
 * <p>键里带 {@link PermissionCache#globalVersion()}：停用货主后由
 * {@code OwnerSessionInvalidator} 把版本号加一，所有旧键立即不再命中，下一次请求即回源拿到新状态；
 * 版本号机制失败时仍有 {@value #TTL_SECONDS} 秒的 TTL 兜底，两条路径都远小于访问令牌有效期。
 *
 * <p>Redis 不可用时<strong>直接读权威库</strong>（不按「未命中」放行，也不按「不可用」拦全仓）：
 * 账号与货主状态都在 PostgreSQL，缓存只是加速；把缓存故障变成登录故障或安全放行都不合口径。
 */
@Component
public class OwnerAccessCache implements OwnerSessionGuard {

    private static final Logger log = LoggerFactory.getLogger(OwnerAccessCache.class);

    private static final String KEY = "hub:owner:usable:%d:%d";
    private static final int TTL_SECONDS = 60;
    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);

    private final StringRedisTemplate redis;
    private final PermissionCache permissionCache;
    private final AccountRepository accountRepository;
    private final OwnerBindingDirectory owners;

    public OwnerAccessCache(StringRedisTemplate redis, PermissionCache permissionCache,
                            AccountRepository accountRepository, OwnerBindingDirectory owners) {
        this.redis = redis;
        this.permissionCache = permissionCache;
        this.accountRepository = accountRepository;
        this.owners = owners;
    }

    @Override
    public boolean usable(Long accountId) {
        if (accountId == null) {
            return false;
        }
        try {
            String key = String.format(KEY, accountId, permissionCache.globalVersion());
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return "1".equals(cached);
            }
            boolean usable = load(accountId);
            redis.opsForValue().set(key, usable ? "1" : "0", TTL);
            return usable;
        } catch (DataAccessException e) {
            log.warn("货主可用性缓存不可用，回源权威库账号 {}：{}", accountId, e.getMessage());
            return load(accountId);
        }
    }

    /** 回源：账号存在且（未绑货主 或 货主仍可用）。 */
    private boolean load(Long accountId) {
        return accountRepository.findById(accountId)
            .map(this::usableAccount)
            .orElse(false);
    }

    private boolean usableAccount(Account account) {
        if (account.ownerId() == null) {
            // 非货主端账号不参与本判定；过滤器只在货主端令牌上提问，这里保持与账号自身状态无关。
            return true;
        }
        // 档案缺失（存量未补录）不按停用处理：停用是唯一阻断条件（契约 1.7）。
        return owners.find(account.ownerId()).map(OwnerBindingDirectory.OwnerBinding::active)
            .orElse(true);
    }
}
