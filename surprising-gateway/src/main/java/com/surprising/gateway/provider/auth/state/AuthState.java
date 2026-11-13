package com.surprising.gateway.provider.auth.state;

import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 登录态读写入口：Valkey 是"这个会话/用户/API Key 还能不能用"的唯一依据，前面挡一层很短的进程内缓存。
 * <ul>
 *   <li>读：进程内缓存（几秒）→ Valkey；Valkey 里没有就是无效，不回查 Postgres。</li>
 *   <li>Valkey 出错：进程内已有的条目在宽限期内继续使用；没有的抛 {@link AuthStateUnavailableException}（401）。</li>
 *   <li>写/撤销：先改 Valkey，再通过 {@code auth:invalidate} 频道通知所有网关清掉本地缓存。</li>
 *   <li>会话滑动续期：最后一次请求后保留 N 天，同一会话至少隔 sessionRenewInterval 才写一次 Valkey。</li>
 * </ul>
 */
@Component
public class AuthState {

    public static final String INVALIDATE_CHANNEL = "auth:invalidate";
    private static final Logger log = LoggerFactory.getLogger(AuthState.class);
    private static final int MAX_LOCAL_ENTRIES = 100_000;

    private final AuthStateBackend backend;
    private final GatewayProperties properties;
    private final LongSupplier clock;
    private final LocalCache<SessionState> sessions = new LocalCache<>();
    private final LocalCache<UserState> users = new LocalCache<>();
    private final LocalCache<ApiKeyState> apiKeys = new LocalCache<>();

    @Autowired
    public AuthState(AuthStateBackend backend, GatewayProperties properties) {
        this(backend, properties, System::currentTimeMillis);
    }

    public AuthState(AuthStateBackend backend, GatewayProperties properties, LongSupplier clock) {
        this.backend = backend;
        this.properties = properties;
        this.clock = clock;
        backend.subscribe(INVALIDATE_CHANNEL, this::onInvalidate);
    }

    // ---------------------------------------------------------------- 会话

    /** 请求路径：读取会话并按需滑动续期；会话不存在返回 null。 */
    public SessionState session(long sessionId) {
        SessionState state = lookup(sessions, sessionKey(sessionId), () -> loadSession(sessionId));
        if (state == null) return state;
        long now = clock.getAsLong();
        if (now - state.renewedAtMillis() < properties.getSecurity().getSessionRenewInterval().toMillis()) return state;
        try {
            boolean alive = backend.renewSession(sessionKey(sessionId), userKey(state.userId()),
                    Duration.ofSeconds(state.ttlSeconds()), now);
            if (!alive) {
                sessions.remove(sessionKey(sessionId));
                return null;
            }
            SessionState renewed = state.renewedAt(now);
            sessions.put(sessionKey(sessionId), renewed, now);
            return renewed;
        } catch (AuthStateUnavailableException ex) {
            return state;
        }
    }

    public void startSession(SessionState state, Duration ttl) {
        backend.hashPut(sessionKey(state.sessionId()), state.toFields(), ttl);
        // 顺带把该用户的状态键续到同样的有效期（常驻的用户状态不受影响）
        backend.renewSession(sessionKey(state.sessionId()), userKey(state.userId()), ttl, clock.getAsLong());
        sessions.remove(sessionKey(state.sessionId()));
    }

    public boolean sessionExists(long sessionId) {
        return backend.exists(sessionKey(sessionId));
    }

    public void endSessions(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) return;
        backend.delete(sessionIds.stream().map(AuthState::sessionKey).toArray(String[]::new));
        for (long sessionId : sessionIds) {
            sessions.remove(sessionKey(sessionId));
            broadcast("session:" + sessionId);
        }
    }

    private SessionState loadSession(long sessionId) {
        Map<String, String> fields = backend.hashGet(sessionKey(sessionId));
        return fields.isEmpty() ? null : SessionState.fromFields(sessionId, fields);
    }

    // ---------------------------------------------------------------- 用户

    public UserState user(long userId) {
        return lookup(users, userKey(userId), () -> {
            Map<String, String> fields = backend.hashGet(userKey(userId));
            return fields.isEmpty() ? null : UserState.fromFields(userId, fields);
        });
    }

    /** 写入用户状态；有 API Key 的用户常驻，其他用户使用会话滑动有效期。 */
    public void putUser(UserState state) {
        Duration ttl = state.hasApiKey() ? null : properties.getSecurity().getSessionTtl();
        backend.hashPut(userKey(state.userId()), state.toFields(), ttl);
        users.remove(userKey(state.userId()));
        broadcast("user:" + state.userId());
    }

    public void deleteUser(long userId) {
        backend.delete(userKey(userId));
        users.remove(userKey(userId));
        broadcast("user:" + userId);
    }

    public boolean userExists(long userId) {
        return backend.exists(userKey(userId));
    }

    // ---------------------------------------------------------------- API Key

    public ApiKeyState apiKey(String apiKey) {
        return lookup(apiKeys, apiKeyKey(apiKey), () -> {
            Map<String, String> fields = backend.hashGet(apiKeyKey(apiKey));
            return fields.isEmpty() ? null : ApiKeyState.fromFields(apiKey, fields);
        });
    }

    public void putApiKey(ApiKeyState state) {
        backend.hashPut(apiKeyKey(state.apiKey()), state.toFields(), null);
        apiKeys.remove(apiKeyKey(state.apiKey()));
        broadcast("apikey:" + state.apiKey());
    }

    public void deleteApiKey(String apiKey) {
        backend.delete(apiKeyKey(apiKey));
        apiKeys.remove(apiKeyKey(apiKey));
        broadcast("apikey:" + apiKey);
    }

    public boolean apiKeyExists(String apiKey) {
        return backend.exists(apiKeyKey(apiKey));
    }

    // ---------------------------------------------------------------- 内部

    static String sessionKey(long sessionId) { return "auth:session:" + sessionId; }
    static String userKey(long userId) { return "auth:user:" + userId; }
    static String apiKeyKey(String apiKey) { return "auth:apikey:" + apiKey; }

    private <V> V lookup(LocalCache<V> cache, String key, java.util.function.Supplier<V> remote) {
        long now = clock.getAsLong();
        LocalCache.Entry<V> cached = cache.entry(key);
        if (cached != null && now - cached.loadedAtMillis() < properties.getSecurity().getAuthLocalCacheTtl().toMillis()) {
            return cached.value();
        }
        try {
            V value = remote.get();
            if (value == null) cache.remove(key);
            else cache.put(key, value, now);
            return value;
        } catch (AuthStateUnavailableException ex) {
            if (cached != null && now - cached.loadedAtMillis() < properties.getSecurity().getAuthLocalStaleTtl().toMillis()) {
                return cached.value();
            }
            throw ex;
        }
    }

    private void broadcast(String message) {
        try {
            backend.publish(INVALIDATE_CHANNEL, message);
        } catch (AuthStateUnavailableException ex) {
            log.warn("auth invalidation broadcast failed; other nodes expire local cache by TTL: {}", message);
        }
    }

    private void onInvalidate(String message) {
        int split = message.indexOf(':');
        if (split < 0) return;
        String kind = message.substring(0, split);
        String id = message.substring(split + 1);
        switch (kind) {
            case "session" -> sessions.remove("auth:session:" + id);
            case "user" -> users.remove("auth:user:" + id);
            case "apikey" -> apiKeys.remove("auth:apikey:" + id);
            default -> { }
        }
    }

    /** 有上限的进程内缓存；满了就整体清空，下一次请求重新从 Valkey 读取。 */
    private static final class LocalCache<V> {
        record Entry<V>(V value, long loadedAtMillis) {}

        private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();

        Entry<V> entry(String key) { return entries.get(key); }

        void put(String key, V value, long now) {
            if (entries.size() >= MAX_LOCAL_ENTRIES) entries.clear();
            entries.put(key, new Entry<>(value, now));
        }

        void remove(String key) { entries.remove(key); }
    }
}
