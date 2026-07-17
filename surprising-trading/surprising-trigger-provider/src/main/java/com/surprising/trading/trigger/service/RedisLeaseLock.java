package com.surprising.trading.trigger.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Token-owned Redis lease used only to avoid duplicate index rebuild work across provider nodes. */
@Component
public class RedisLeaseLock {

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

    public RedisLeaseLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Lease tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? new Lease(key, token) : null;
    }

    public void release(Lease lease) {
        if (lease != null) {
            redisTemplate.execute(RELEASE, List.of(lease.key()), lease.token());
        }
    }

    public boolean renew(Lease lease, Duration ttl) {
        if (lease == null) {
            return false;
        }
        Long renewed = redisTemplate.execute(
                RENEW, List.of(lease.key()), lease.token(), Long.toString(ttl.toMillis()));
        return renewed != null && renewed == 1L;
    }

    public record Lease(String key, String token) {
    }
}
