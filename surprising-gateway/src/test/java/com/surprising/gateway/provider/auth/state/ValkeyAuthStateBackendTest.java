package com.surprising.gateway.provider.auth.state;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 真实 Valkey 上验证 Lua 脚本、TTL 和 Pub/Sub 失效广播（需要 GATEWAY_LIMIT_TEST_REDIS_PORT 指向一个临时实例）。 */
@EnabledIfEnvironmentVariable(named = "GATEWAY_LIMIT_TEST_REDIS_PORT", matches = "[0-9]+")
class ValkeyAuthStateBackendTest {

    private static final long 会话 = 9_000_000_001L;
    private static final long 用户 = 9_000_000_002L;

    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    ValkeyAuthStateBackend backend;
    AuthState state;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getenv("GATEWAY_LIMIT_TEST_REDIS_PORT"))));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        backend = new ValkeyAuthStateBackend(redis);
        state = new AuthState(backend, new GatewayProperties());
        cleanup();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
        backend.stop();
        factory.destroy();
    }

    private void cleanup() {
        redis.delete(List.of("auth:session:" + 会话, "auth:user:" + 用户, "auth:apikey:sx_contract"));
    }

    @Test
    void 会话和用户使用滑动过期_常驻用户没有过期时间() {
        state.putUser(AuthStateFixture.user(用户, "NORMAL", List.of("USER"), List.of("a.b")));
        long now = System.currentTimeMillis();
        state.startSession(new SessionState(会话, 用户, null, now, 7 * 86400L, now), Duration.ofDays(7));
        assertThat(redis.getExpire("auth:session:" + 会话, java.util.concurrent.TimeUnit.SECONDS)).isBetween(7 * 86400L - 5, 7 * 86400L);
        assertThat(redis.getExpire("auth:user:" + 用户, java.util.concurrent.TimeUnit.SECONDS)).isBetween(7 * 86400L - 5, 7 * 86400L);
        assertThat(state.user(用户).permissions()).containsExactly("a.b");

        state.putUser(new UserState(用户, "maker", null, null, java.time.Instant.EPOCH, "NORMAL", List.of("USER"),
                List.of(), new java.util.LinkedHashSet<>(), new java.util.LinkedHashSet<>(), true));
        assertThat(redis.getExpire("auth:user:" + 用户)).isEqualTo(-1L);
        assertThat(backend.renewSession("auth:session:" + 会话, "auth:user:" + 用户, Duration.ofHours(1), now)).isTrue();
        assertThat(redis.getExpire("auth:session:" + 会话)).isLessThanOrEqualTo(3600L);
        assertThat(redis.getExpire("auth:user:" + 用户)).as("常驻用户不被续期脚本加上过期时间").isEqualTo(-1L);
        assertThat(backend.renewSession("auth:session:none", "auth:user:" + 用户, Duration.ofHours(1), now)).isFalse();
    }

    @Test
    void 删除会话后另一个节点通过广播立即清掉本地缓存() {
        var other = new AuthState(new ValkeyAuthStateBackend(redis), new GatewayProperties());
        long now = System.currentTimeMillis();
        state.putUser(AuthStateFixture.user(用户, "NORMAL", List.of("USER"), List.of()));
        state.startSession(new SessionState(会话, 用户, null, now, 3600, now), Duration.ofHours(1));
        assertThat(other.session(会话)).isNotNull();
        state.endSessions(List.of(会话));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(other.session(会话)).isNull());
    }

    @Test
    void API_Key哈希原样往返且没有过期时间() {
        var key = new ApiKeyState("sx_contract", java.util.UUID.randomUUID(), 用户, "READ,TRADE", "203.0.113.7", "cipher-text");
        state.putApiKey(key);
        var loaded = new AuthState(backend, new GatewayProperties()).apiKey("sx_contract");
        assertThat(loaded).isEqualTo(key);
        assertThat(redis.getExpire("auth:apikey:sx_contract")).isEqualTo(-1L);
    }

    @Test
    void 连不上Valkey时抛出统一的不可用异常() {
        var broken = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        broken.afterPropertiesSet();
        try {
            var offline = new ValkeyAuthStateBackend(new StringRedisTemplate(broken));
            assertThatThrownBy(() -> offline.hashGet("auth:user:1")).isInstanceOf(AuthStateUnavailableException.class);
        } finally {
            broken.destroy();
        }
    }
}
