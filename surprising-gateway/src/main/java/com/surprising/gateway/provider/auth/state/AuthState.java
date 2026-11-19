package com.surprising.gateway.provider.auth.state;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongFunction;
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
    /** 永久绑定不等于永久本机缓存；短缓存确保解绑和封禁可以跨节点及时生效。 */
    private final LocalCache<Long> telegramIds = new LocalCache<>();
    private final LongFunction<SessionState> sessionLoader = this::loadSession;
    private final LongFunction<UserState> userLoader = this::loadUser;
    private final Function<String, ApiKeyState> apiKeyLoader = this::loadApiKey;

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
        String key = sessionKey(sessionId);
        SessionState state = lookupLong(sessions, key, sessionId, sessionLoader);
        if (state == null) return state;
        long now = clock.getAsLong();
        if (now - state.renewedAtMillis() < properties.getSecurity().getSessionRenewInterval().toMillis()) return state;
        LocalCache.Entry<SessionState> cached = sessions.entry(key);
        if(cached!=null && now<cached.retryAtMillis) return state;
        try {
            boolean alive = backend.renewSession(key, userKey(state.userId()),
                    Duration.ofSeconds(state.ttlSeconds()), now, properties.getSecurity().getSessionRenewInterval());
            if (!alive) {
                sessions.remove(key);
                return null;
            }
            SessionState renewed = state.renewedAt(now);
            sessions.put(key, renewed, now);
            return renewed;
        } catch (AuthStateUnavailableException ex) {
            if(cached!=null) cached.retryAtMillis=now+1000;
            return state;
        }
    }

    public void startSession(SessionState state, Duration ttl) {
        String key = sessionKey(state.sessionId());
        backend.hashPut(key, state.toFields(), ttl);
        sessions.remove(key);
    }

    /** 更新已登录会话属性（如当前操作账户）并通知各节点丢掉本地缓存。 */
    public void replaceSession(SessionState state, Duration ttl) {
        String key = sessionKey(state.sessionId());
        backend.hashPut(key, state.toFields(), ttl);
        sessions.remove(key);
        broadcast("session:" + state.sessionId());
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
        return lookupLong(users, userKey(userId), userId, userLoader);
    }

    private UserState loadUser(long userId) {
        Map<String, String> fields = backend.hashGet(userKey(userId));
        return fields.isEmpty() ? null : UserState.fromFields(userId, fields);
    }

    /** 写入用户状态；有 API Key 或 Telegram 绑定的用户常驻，其他用户使用会话滑动有效期。 */
    public void putUser(UserState state) {
        Duration ttl = (state.hasApiKey() || state.telegramBound()) ? null : properties.getSecurity().getSessionTtl();
        String key = userKey(state.userId());
        backend.hashPut(key, state.toFields(), ttl);
        users.remove(key);
        broadcast("user:" + state.userId());
    }

    public void deleteUser(long userId) {
        String key = userKey(userId);
        backend.delete(key);
        users.remove(key);
        broadcast("user:" + userId);
    }

    public boolean userExists(long userId) {
        return backend.exists(userKey(userId));
    }

    // ---------------------------------------------------------------- API Key

    public ApiKeyState apiKey(String apiKey) {
        return lookupString(apiKeys, apiKeyKey(apiKey), apiKey, apiKeyLoader);
    }

    private ApiKeyState loadApiKey(String apiKey) {
        Map<String, String> fields = backend.hashGet(apiKeyKey(apiKey));
        return fields.isEmpty() ? null : ApiKeyState.fromFields(apiKey, fields);
    }

    public void putApiKey(ApiKeyState state) {
        String key = apiKeyKey(state.apiKey());
        backend.hashPut(key, state.toFields(), null);
        apiKeys.remove(key);
        broadcast("apikey:" + state.apiKey());
    }

    public void deleteApiKey(String apiKey) {
        String key = apiKeyKey(apiKey);
        backend.delete(key);
        apiKeys.remove(key);
        broadcast("apikey:" + apiKey);
    }

    public boolean apiKeyExists(String apiKey) {
        return backend.exists(apiKeyKey(apiKey));
    }

    // ---------------------------------------------------------------- Telegram 身份

    /** 永久绑定：本机短缓存 → Valkey；缺失就是未知身份，不回源数据库。 */
    public Long telegramUserId(String subject) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("telegram subject is required");
        return lookupString(telegramIds, telegramKey(subject), subject, value -> {
            String uid = backend.hashGet(telegramKey(value)).get("uid");
            return uid == null ? null : Long.parseLong(uid);
        });
    }

    /** 绑定提交成功后写入 Valkey，并通知其他网关丢掉本机条目。 */
    public void putTelegramIdentity(String subject, long userId) {
        String key = telegramKey(subject);
        backend.hashPut(key, Map.of("uid", Long.toString(userId)), null);
        telegramIds.remove(key);
        broadcast("telegram:" + subject);
    }

    public void deleteTelegramIdentity(String subject) {
        backend.delete(telegramKey(subject));
        telegramIds.remove(telegramKey(subject));
        broadcast("telegram:" + subject);
    }

    // ---------------------------------------------------------------- 内部

    static String sessionKey(long sessionId) { return "auth:session:" + sessionId; }
    static String userKey(long userId) { return "auth:user:" + userId; }
    static String apiKeyKey(String apiKey) { return "auth:apikey:" + apiKey; }
    static String telegramKey(String subject) { return "auth:telegram:" + subject; }

    private <V> V lookupLong(LocalCache<V> cache, String key, long id, LongFunction<V> remote) {
        long now = clock.getAsLong();
        LocalCache.Entry<V> cached = cache.entry(key);
        if (cached != null && (now - cached.loadedAtMillis() < properties.getSecurity().getAuthLocalCacheTtl().toMillis()
                || (now < cached.retryAtMillis && now-cached.loadedAtMillis()<properties.getSecurity().getAuthLocalStaleTtl().toMillis()))) {
            return cached.value();
        }
        try {
            V value = remote.apply(id);
            if (value == null) cache.remove(key);
            else cache.put(key, value, now);
            return value;
        } catch (AuthStateUnavailableException ex) {
            if (cached != null && now - cached.loadedAtMillis() < properties.getSecurity().getAuthLocalStaleTtl().toMillis()) {
                cached.retryAtMillis=now+1000;
                return cached.value();
            }
            throw ex;
        }
    }

    private <V> V lookupString(LocalCache<V> cache, String key, String id, Function<String, V> remote) {
        long now = clock.getAsLong();
        LocalCache.Entry<V> cached = cache.entry(key);
        if (cached != null && (now - cached.loadedAtMillis() < properties.getSecurity().getAuthLocalCacheTtl().toMillis()
                || (now < cached.retryAtMillis && now-cached.loadedAtMillis()<properties.getSecurity().getAuthLocalStaleTtl().toMillis()))) {
            return cached.value();
        }
        try {
            V value = remote.apply(id);
            if (value == null) cache.remove(key);
            else cache.put(key, value, now);
            return value;
        } catch (AuthStateUnavailableException ex) {
            if (cached != null && now - cached.loadedAtMillis() < properties.getSecurity().getAuthLocalStaleTtl().toMillis()) {
                cached.retryAtMillis=now+1000;
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
            case "telegram" -> telegramIds.remove("auth:telegram:" + id);
            default -> { }
        }
    }

    /** 有上限的短缓存；满时只逐条腾位置，避免整体清空打出请求尖峰。 */
    private static final class LocalCache<V> {
        private static final class Entry<V> {
            private final V value;
            private final long loadedAtMillis;
            private volatile long retryAtMillis;
            Entry(V value,long loadedAtMillis) { this.value=value;this.loadedAtMillis=loadedAtMillis; }
            V value() { return value; }
            long loadedAtMillis() { return loadedAtMillis; }
        }

        private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();

        Entry<V> entry(String key) { return entries.get(key); }

        void put(String key, V value, long now) {
            if (entries.size() >= MAX_LOCAL_ENTRIES && !entries.containsKey(key)) {
                var iterator=entries.keySet().iterator();
                if(iterator.hasNext()) entries.remove(iterator.next());
            }
            entries.put(key, new Entry<>(value, now));
        }

        void remove(String key) { entries.remove(key); }
    }
}
