package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.owner.domain.OwnerStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 货主状态变更后的会话失效（design「账号与登录」的「会话缓存的失效」）。
 *
 * <p>停用或启用<strong>提交之后</strong>做两件事：
 * <ol>
 *   <li>把全局权限版本号加一——{@link OwnerAccessCache} 的键含版本号，旧键立即不命中，
 *       下一次请求必然回源拿到新的货主状态；</li>
 *   <li>按货主撤销该货主下所有账号的刷新令牌——否则旧刷新令牌还能换出新的访问令牌。</li>
 * </ol>
 *
 * <p>为什么在提交后：动作本身已成功，会话失效只是让它尽快生效；此处失败只能告警，
 * 不能把已提交的停用动作回滚（design：失败只告警不回滚）。因此本方法自己吞掉异常并记错误日志，
 * 不让异常冒泡回调用方——提交后阶段的同步监听抛错会被当成业务失败，与设计口径不符。
 */
@Component
public class OwnerSessionInvalidator {

    private static final Logger log = LoggerFactory.getLogger(OwnerSessionInvalidator.class);

    private final PermissionCache permissionCache;
    private final RefreshTokenStore refreshTokenStore;
    private final com.huoling.wms.masterdata.identity.domain.AccountRepository accountRepository;

    public OwnerSessionInvalidator(PermissionCache permissionCache, RefreshTokenStore refreshTokenStore,
                                   com.huoling.wms.masterdata.identity.domain.AccountRepository
                                       accountRepository) {
        this.permissionCache = permissionCache;
        this.refreshTokenStore = refreshTokenStore;
        this.accountRepository = accountRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(OwnerStatusChangedEvent event) {
        try {
            permissionCache.bumpVersion();
            List<Long> accountIds = accountRepository.findIdsByOwnerId(Long.parseLong(event.ownerId()));
            accountIds.forEach(refreshTokenStore::revokeAll);
            log.info("货主 {} 状态变更为 {}，已失效会话缓存并撤销 {} 个账号的刷新令牌（eventId={}）",
                event.ownerNo(), event.status(), accountIds.size(), event.eventId());
        } catch (RuntimeException e) {
            log.error("货主 {} 状态变更后的会话失效失败，需人工按 eventId={} 复核：{}",
                event.ownerNo(), event.eventId(), e.getMessage());
        }
    }
}
