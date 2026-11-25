package com.huoling.wms.masterdata.identity.application;

import com.huoling.wms.masterdata.identity.domain.ClientType;
import com.huoling.wms.masterdata.identity.domain.PermissionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 权限集缓存。键含全局版本号，任何授权动作让版本号加一，旧键自然不再命中（决三）。
 *
 * <p>键里带端标识：同一账号在不同端加载的是不同角色集，按端分开缓存，
 * 否则货主端会读到运营端的权限集（或反过来），端间权限就串了。
 */
@Component
public class PermissionCache {

    private static final String VERSION_KEY = "hub:perm:version";
    private static final String PERM_KEY = "hub:perm:%d:%s:%d";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final PermissionRepository permissionRepository;

    public PermissionCache(StringRedisTemplate redis, PermissionRepository permissionRepository) {
        this.redis = redis;
        this.permissionRepository = permissionRepository;
    }

    public Set<String> permissionsOf(Long accountId, ClientType client) {
        ClientType end = client == null ? ClientType.HUB : client;
        long version = globalVersion();
        String key = String.format(PERM_KEY, accountId, end.name(), version);
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            if (cached.isEmpty()) {
                return Set.of();
            }
            return new HashSet<>(Arrays.asList(cached.split(",")));
        }
        Set<String> permissions = permissionRepository.findCodesByAccount(accountId, end);
        redis.opsForValue().set(key, String.join(",", permissions), TTL);
        return permissions;
    }

    public long globalVersion() {
        String value = redis.opsForValue().get(VERSION_KEY);
        if (value == null) {
            redis.opsForValue().setIfAbsent(VERSION_KEY, "1");
            value = redis.opsForValue().get(VERSION_KEY);
        }
        return value == null ? 1L : Long.parseLong(value);
    }

    /** 授权动作提交后调用，使全部旧缓存键失效。 */
    public void bumpVersion() {
        redis.opsForValue().increment(VERSION_KEY);
    }
}
