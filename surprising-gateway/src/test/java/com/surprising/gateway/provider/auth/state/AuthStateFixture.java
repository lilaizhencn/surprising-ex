package com.surprising.gateway.provider.auth.state;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 测试辅助：一个内存 Valkey 加一个 AuthState，并能快速造出"已登录"的会话、用户和 API Key。 */
public final class AuthStateFixture {

    public final InMemoryAuthStateBackend backend = new InMemoryAuthStateBackend();
    public final GatewayProperties properties;
    public final AuthState state;

    public AuthStateFixture() {
        this(new GatewayProperties());
    }

    public AuthStateFixture(GatewayProperties properties) {
        this.properties = properties;
        this.state = new AuthState(backend, properties, backend.clock::get);
    }

    public static AuthState newState(GatewayProperties properties) {
        return new AuthStateFixture(properties).state;
    }

    public static UserState user(long userId, String status, List<String> roles, List<String> permissions) {
        return new UserState(userId, "user" + userId, "u" + userId + "@example.com", null, Instant.EPOCH,
                status, roles, permissions, new LinkedHashSet<>(), new LinkedHashSet<>(), false);
    }

    public static UserState userWithBlockedIps(long userId, Set<String> blockedIps) {
        return new UserState(userId, "user" + userId, null, null, Instant.EPOCH, "NORMAL", List.of("USER"),
                List.of(), blockedIps, new LinkedHashSet<>(), false);
    }

    /** 把一条 API Key 记录写进 Valkey（同时确保所属用户存在且状态正常）。 */
    public void putApiKey(com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository.ApiKeyRecord record) {
        state.putApiKey(new ApiKeyState(record.apiKey(), record.apiKeyId(), record.userId(), record.permissions(),
                record.ipAllowlist(), record.secretCiphertext()));
        if (!state.userExists(record.userId())) {
            state.putUser(new UserState(record.userId(), "user" + record.userId(), null, null, Instant.EPOCH, "NORMAL",
                    List.of("USER"), List.of(), new LinkedHashSet<>(), new LinkedHashSet<>(), true));
        }
    }

    /** 写入一个正常用户和一个 7 天滑动会话。 */
    public SessionState login(long userId, long sessionId) {
        state.putUser(user(userId, "NORMAL", List.of("USER"), List.of()));
        return startSession(userId, sessionId);
    }

    public SessionState startSession(long userId, long sessionId) {
        long now = backend.clock.get();
        var session = new SessionState(sessionId, userId, null, now, properties.getSecurity().getSessionTtl().toSeconds(), now);
        state.startSession(session, properties.getSecurity().getSessionTtl());
        return session;
    }
}
