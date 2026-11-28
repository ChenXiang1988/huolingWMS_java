package com.huoling.wms.masterdata.identity.domain;

/**
 * 会话可用性判定端口：货主端令牌在每次请求上被复查一次（契约 1.7、design「账号与登录」）。
 *
 * <p>访问令牌里只有身份，没有「账号此刻是否仍可用」——货主停用后已签发的令牌必须在下一次请求被拒，
 * 因此第一道门每请求问一次本端口。实现带缓存（键含全局权限版本号，TTL 兜底），
 * 停用动作提交后版本号一 bump，下一次请求就回源到库里拿到 {@code DISABLED}。
 *
 * <p>端口放 domain、实现放 application：过滤器（api 层）只依赖这个布尔判定，
 * 不关心判定背后是缓存还是直查库。
 */
public interface OwnerSessionGuard {

    /**
     * 账号所属货主是否仍允许该账号继续使用令牌。
     *
     * @return {@code false} 表示账号已不可用（货主停用或账号不存在），调用方按 401 {@code AUTH_1003} 拒绝
     */
    boolean usable(Long accountId);
}
