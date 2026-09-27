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
        return targets(List.of(route), now).get(route);
    }

    /** One bounded router drain reads fresh leases in two pipelined round trips. No lease cache. */
    public Map<RealtimeRoute, Map<String, String>> targets(
            Collection<RealtimeRoute> requested, long now) {
        if (requested.isEmpty()) return Map.of();
        var exact = new LinkedHashSet<>(requested);
        for (var route : requested)
            if (route.userId() == 0 && !route.symbol().equals("*"))
                exact.add(new RealtimeRoute(route.productLine(), 0, route.channel(), "*"));
        var ordered = new ArrayList<>(exact);
        var memberships = redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            for (var route : ordered) {
                byte[] key = bytes("rt:route:{" + route.key() + "}");
                connection.zSetCommands().zRemRangeByScore(key, 0, now);
                connection.zSetCommands().zRangeByScore(key, now + 1, Double.POSITIVE_INFINITY);
            }
            return null;
        });
        var members = new HashMap<RealtimeRoute, Set<String>>();
        var nodeIds = new LinkedHashSet<String>();
        for (int i = 0; i < ordered.size(); i++) {
            @SuppressWarnings("unchecked")
            var nodes = (Set<String>) memberships.get(i * 2 + 1);
            members.put(ordered.get(i), nodes);
            nodeIds.addAll(nodes);
        }
        var nodes = new ArrayList<>(nodeIds);
        var endpoints = nodes.isEmpty() ? List.of() : redis.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (String node : nodes) connection.stringCommands().get(bytes("rt:node:" + node));
                    return null;
                });
        var liveNodes = new HashMap<String, String>();
        for (int i = 0; i < nodes.size(); i++)
            if (endpoints.get(i) != null) liveNodes.put(nodes.get(i), (String) endpoints.get(i));
        var result = new HashMap<RealtimeRoute, Map<String, String>>();
        for (var route : requested) {
            var targets = new LinkedHashMap<String, String>();
            for (String node : members.get(route))
                if (liveNodes.containsKey(node)) targets.put(node, liveNodes.get(node));
            if (route.userId() == 0 && !route.symbol().equals("*"))
                for (String node : members.get(new RealtimeRoute(route.productLine(), 0, route.channel(), "*")))
                    if (liveNodes.containsKey(node)) targets.put(node, liveNodes.get(node));
            result.put(route, targets);
        }
        return result;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public void removeNode(String node) {
        redis.delete("rt:node:" + node);
    }

    private static void requireNode(String node) {
        java.util.UUID.fromString(node);
    }
}
