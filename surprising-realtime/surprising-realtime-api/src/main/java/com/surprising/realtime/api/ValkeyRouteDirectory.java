package com.surprising.realtime.api;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;

/** Nodes use a fresh boot UUID. Membership is a lease, never a list of user sessions. */
public final class ValkeyRouteDirectory {
    private final StringRedisTemplate redis;
    private final java.util.concurrent.atomic.AtomicLong version =
            new java.util.concurrent.atomic.AtomicLong();
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long>
            MEMBERSHIP =
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                            """
                            local prior=redis.call('HGET',KEYS[2],ARGV[1])
                            if prior and prior>=ARGV[2] then return 0 end
                            redis.call('HSET',KEYS[2],ARGV[1],ARGV[2])
                            if ARGV[3]=='0' then redis.call('ZREM',KEYS[1],ARGV[1]) else redis.call('ZADD',KEYS[1],ARGV[3],ARGV[1]) end
                            redis.call('EXPIRE',KEYS[1],120);redis.call('EXPIRE',KEYS[2],120)
                            return 1
                            """,
                            Long.class);

    public ValkeyRouteDirectory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void heartbeat(String node, String endpoint, Duration lease) {
        requireNode(node);
        if (!endpoint.startsWith("aeron:udp?endpoint="))
            throw new IllegalArgumentException("UDP node endpoint required");
        redis.opsForValue().set("rt:node:" + node, endpoint, lease);
    }

    public void register(RealtimeRoute route, String node, long expiresAt) {
        membership(route, node, expiresAt, version.incrementAndGet());
    }

    public void unregister(RealtimeRoute route, String node) {
        membership(route, node, 0, version.incrementAndGet());
    }

    void membership(RealtimeRoute route, String node, long expiresAt, long updateVersion) {
        requireNode(node);
        String key = "rt:route:{" + route.key() + "}";
        redis.execute(
                MEMBERSHIP,
                List.of(key, key + ":versions"),
                node,
                RealtimeVersion.of(updateVersion, 0),
                Long.toString(expiresAt));
    }

    public Map<String, String> targets(RealtimeRoute route, long now) {
        var result = new LinkedHashMap<>(exactTargets(route, now));
        if (route.userId() == 0 && !route.symbol().equals("*"))
            result.putAll(
                    exactTargets(
                            new RealtimeRoute(route.productLine(), 0, route.channel(), "*"), now));
        return result;
    }

    private Map<String, String> exactTargets(RealtimeRoute route, long now) {
        String key = "rt:route:{" + route.key() + "}";
        redis.opsForZSet().removeRangeByScore(key, 0, now);
        var nodes = redis.opsForZSet().rangeByScore(key, now + 1, Double.POSITIVE_INFINITY);
        if (nodes == null || nodes.isEmpty()) return Map.of();
        var endpoints =
                redis.opsForValue().multiGet(nodes.stream().map(n -> "rt:node:" + n).toList());
        var result = new LinkedHashMap<String, String>();
        int i = 0;
        for (String node : nodes) {
            String endpoint = endpoints == null ? null : endpoints.get(i++);
            if (endpoint != null) result.put(node, endpoint);
        }
        return result;
    }

    public void removeNode(String node) {
        redis.delete("rt:node:" + node);
    }

    private static void requireNode(String node) {
        java.util.UUID.fromString(node);
    }
}
