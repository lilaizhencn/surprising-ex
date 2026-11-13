package com.surprising.gateway.provider.auth.state;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 测试用的内存 Valkey：支持过期（可控时钟）、发布订阅、模拟宕机和读写次数统计。 */
public final class InMemoryAuthStateBackend implements AuthStateBackend {

    private record Entry(Map<String, String> fields, long expiresAtMillis) {}

    public final AtomicLong clock = new AtomicLong(1_000_000L);
    public volatile boolean down;
    public final List<String> published = new ArrayList<>();
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, List<Consumer<String>>> listeners = new HashMap<>();
    public int reads;
    public int writes;

    private void check() {
        if (down) throw new AuthStateUnavailableException("simulated valkey outage", null);
    }

    private synchronized Entry live(String key) {
        Entry entry = entries.get(key);
        if (entry != null && entry.expiresAtMillis() <= clock.get()) {
            entries.remove(key);
            return null;
        }
        return entry;
    }

    public synchronized long ttlMillis(String key) {
        Entry entry = live(key);
        return entry == null ? -2 : entry.expiresAtMillis() == Long.MAX_VALUE ? -1 : entry.expiresAtMillis() - clock.get();
    }

    @Override public synchronized Map<String, String> hashGet(String key) {
        check();
        reads++;
        Entry entry = live(key);
        return entry == null ? Map.of() : new HashMap<>(entry.fields());
    }

    @Override public synchronized void hashPut(String key, Map<String, String> fields, Duration ttl) {
        check();
        writes++;
        entries.put(key, new Entry(new HashMap<>(fields), ttl == null ? Long.MAX_VALUE : clock.get() + ttl.toMillis()));
    }

    @Override public synchronized boolean exists(String key) {
        check();
        return live(key) != null;
    }

    @Override public synchronized void delete(String... keys) {
        check();
        writes++;
        for (String key : keys) entries.remove(key);
    }

    @Override public synchronized boolean renewSession(String sessionKey, String userKey, Duration ttl, long nowMillis) {
        check();
        writes++;
        Entry session = live(sessionKey);
        if (session == null) return false;
        session.fields().put("renewedAt", Long.toString(nowMillis));
        entries.put(sessionKey, new Entry(session.fields(), clock.get() + ttl.toMillis()));
        Entry user = live(userKey);
        if (user != null && !"1".equals(user.fields().get("persistent"))) {
            entries.put(userKey, new Entry(user.fields(), clock.get() + ttl.toMillis()));
        }
        return true;
    }

    @Override public void publish(String channel, String message) {
        check();
        published.add(message);
        List<Consumer<String>> subscribers;
        synchronized (this) {
            subscribers = new ArrayList<>(listeners.getOrDefault(channel, List.of()));
        }
        subscribers.forEach(listener -> listener.accept(message));
    }

    @Override public synchronized void subscribe(String channel, Consumer<String> listener) {
        listeners.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(listener);
    }
}
