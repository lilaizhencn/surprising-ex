package com.surprising.gateway.provider.auth.state;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 登录态的 Valkey 存取接口。所有读写失败统一抛 {@link AuthStateUnavailableException}。
 * ttl 为 null 表示永不过期。
 */
public interface AuthStateBackend {

    /** 读取整个 hash；key 不存在返回空 map。 */
    Map<String, String> hashGet(String key);

    /** 整体覆盖一个 hash 并设置过期时间（同一次原子操作）。 */
    void hashPut(String key, Map<String, String> fields, Duration ttl);

    boolean exists(String key);

    void delete(String... keys);

    /**
     * 会话续期：会话存在才续，同时续用户键（用户键标记为常驻时不动）。
     * 返回会话是否还在。
     */
    boolean renewSession(String sessionKey, String userKey, Duration ttl, long nowMillis);

    void publish(String channel, String message);

    void subscribe(String channel, Consumer<String> listener);
}
