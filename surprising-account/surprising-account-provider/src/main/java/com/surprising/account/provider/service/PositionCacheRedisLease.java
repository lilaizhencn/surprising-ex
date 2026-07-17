package com.surprising.account.provider.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Token-owned lease used only to prevent duplicate cache rebuild work across account nodes. */
@Component
public class PositionCacheRedisLease {

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public PositionCacheRedisLease(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Lease tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? new Lease(key, token) : null;
    }

    public boolean renew(Lease lease, Duration ttl) {
        if (lease == null) {
            return false;
        }
        Long renewed = redisTemplate.execute(
                RENEW, List.of(lease.key()), lease.token(), Long.toString(ttl.toMillis()));
        return renewed != null && renewed == 1L;
    }

    public void release(Lease lease) {
        if (lease != null) {
            redisTemplate.execute(RELEASE, List.of(lease.key()), lease.token());
        }
    }

    public record Lease(String key, String token) {
    }
}
