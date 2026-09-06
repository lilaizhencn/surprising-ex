package com.surprising.realtime.api;

import com.surprising.product.api.ProductLine;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

public final class ValkeySnapshotRequests {
    private final StringRedisTemplate redis;

    public ValkeySnapshotRequests(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void renew(ProductLine product, long user, long expiresAt) {
        if (product == null || user <= 0)
            throw new IllegalArgumentException("invalid snapshot identity");
        redis.opsForZSet().add("rt:refresh:" + product.name(), Long.toString(user), expiresAt);
    }

    public void renewBook(ProductLine product, String symbol, long expiresAt) {
        if (!symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}"))
            throw new IllegalArgumentException("invalid book symbol");
        redis.opsForZSet().add("rt:books:" + product.name(), symbol, expiresAt);
    }

    public Set<String> books(ProductLine product, long now, long offset, int limit) {
        String key = "rt:books:" + product.name();
        redis.opsForZSet().removeRangeByScore(key, 0, now);
        var symbols =
                redis.opsForZSet()
                        .rangeByScore(key, now + 1, Double.POSITIVE_INFINITY, offset, limit);
        return symbols == null ? Set.of() : symbols;
    }

    public Set<String> active(ProductLine product, long now, long offset, int limit) {
        String key = "rt:refresh:" + product.name();
        redis.opsForZSet().removeRangeByScore(key, 0, now);
        var users =
                redis.opsForZSet()
                        .rangeByScore(key, now + 1, Double.POSITIVE_INFINITY, offset, limit);
        return users == null ? Set.of() : users;
    }
}
