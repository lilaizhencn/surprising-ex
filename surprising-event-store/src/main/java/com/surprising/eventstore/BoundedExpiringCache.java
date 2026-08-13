package com.surprising.eventstore;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class BoundedExpiringCache<K, V> {

    private final long ttlNanos;
    private final int maxEntries;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    public BoundedExpiringCache(Duration ttl, int maxEntries) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("cache ttl must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("cache maxEntries must be positive");
        }
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
    }

    public synchronized V get(K key) {
        Objects.requireNonNull(key, "key");
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtNanos() <= System.nanoTime()) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    public synchronized void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        cleanupExpired(System.nanoTime());
        entries.put(key, new Entry<>(value, expiration(System.nanoTime())));
        while (entries.size() > maxEntries) {
            Iterator<K> iterator = entries.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    public synchronized V putIfAbsent(K key, V value) {
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        put(key, value);
        return null;
    }

    public synchronized V remove(K key) {
        Entry<V> removed = entries.remove(key);
        return removed == null ? null : removed.value();
    }

    public synchronized int size() {
        cleanupExpired(System.nanoTime());
        return entries.size();
    }

    public synchronized void cleanup() {
        cleanupExpired(System.nanoTime());
    }

    private long expiration(long now) {
        try {
            return Math.addExact(now, ttlNanos);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private void cleanupExpired(long now) {
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtNanos() <= now) {
                iterator.remove();
            }
        }
    }

    private record Entry<V>(V value, long expiresAtNanos) {
    }
}
