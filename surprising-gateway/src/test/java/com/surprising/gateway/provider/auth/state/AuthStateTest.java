package com.surprising.gateway.provider.auth.state;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 登录状态的核心规则：滑动续期、失效、本地缓存、Valkey 宕机时的行为。 */
class AuthStateTest {

    private static final long 会话 = 1088L;
    private static final long 用户 = 1L;
    private static final String 会话键 = "auth:session:1088";
    private static final String 用户键 = "auth:user:1";

    AuthStateFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new AuthStateFixture();
        fixture.login(用户, 会话);
    }

    private void 过去(Duration duration) {
        fixture.backend.clock.addAndGet(duration.toMillis());
    }

    @Test
    void 会话滑动续期_十分钟内的请求不写Valkey() {
        var state = fixture.state;
        assertThat(state.session(会话)).isNotNull();
        int writes = fixture.backend.writes;
        过去(Duration.ofMinutes(9));
        assertThat(state.session(会话)).isNotNull();
        assertThat(fixture.backend.writes).as("续期被节流").isEqualTo(writes);
    }

    @Test
    void 会话滑动续期_超过十分钟的请求把会话和用户都续满七天() {
        var state = fixture.state;
        过去(Duration.ofDays(3));
        assertThat(state.session(会话)).isNotNull();
        assertThat(fixture.backend.ttlMillis(会话键)).isEqualTo(Duration.ofDays(7).toMillis());
        assertThat(fixture.backend.ttlMillis(用户键)).isEqualTo(Duration.ofDays(7).toMillis());
        // 又过 6 天仍然有效，说明窗口是从上一次请求算起的
        过去(Duration.ofDays(6));
        assertThat(state.session(会话)).isNotNull();
    }

    @Test
    void 七天没有请求会话过期() {
        过去(Duration.ofDays(7).plusSeconds(1));
        assertThat(fixture.state.session(会话)).isNull();
        assertThat(fixture.state.sessionExists(会话)).isFalse();
    }

    @Test
    void 常驻用户状态不会随会话过期() {
        fixture.state.putUser(new UserState(2, "maker", null, null, java.time.Instant.EPOCH, "NORMAL",
                List.of("USER"), List.of(), new java.util.LinkedHashSet<>(), new java.util.LinkedHashSet<>(), true));
        assertThat(fixture.backend.ttlMillis("auth:user:2")).isEqualTo(-1);
        过去(Duration.ofDays(30));
        assertThat(fixture.state.user(2)).isNotNull();
    }

    @Test
    void 退出登录立即失效_本节点缓存也被清掉() {
        var state = fixture.state;
        assertThat(state.session(会话)).isNotNull(); // 进入本地缓存
        state.endSessions(List.of(会话));
        assertThat(state.session(会话)).isNull();
        assertThat(fixture.backend.published).contains("session:" + 会话);
    }

    @Test
    void 另一个节点的本地缓存收到广播后立即失效() {
        var other = new AuthState(fixture.backend, fixture.properties, fixture.backend.clock::get);
        assertThat(other.session(会话)).isNotNull();
        fixture.state.endSessions(List.of(会话));
        int reads = fixture.backend.reads;
        assertThat(other.session(会话)).isNull();
        assertThat(fixture.backend.reads).as("缓存被清掉后重新读 Valkey").isGreaterThan(reads);
    }

    @Test
    void 用户改动广播给其他节点_封禁和权限变化立即生效() {
        var other = new AuthState(fixture.backend, fixture.properties, fixture.backend.clock::get);
        assertThat(other.user(用户).status()).isEqualTo("NORMAL");
        fixture.state.putUser(AuthStateFixture.user(用户, "FROZEN", List.of("USER"), List.of("admin.users.read")));
        assertThat(other.user(用户).status()).isEqualTo("FROZEN");
        assertThat(other.user(用户).permissions()).containsExactly("admin.users.read");
    }

    @Test
    void API_Key撤销立即生效() {
        var key = new ApiKeyState("sx_key", java.util.UUID.randomUUID(), 用户, "READ", "", "cipher");
        fixture.state.putApiKey(key);
        var other = new AuthState(fixture.backend, fixture.properties, fixture.backend.clock::get);
        assertThat(other.apiKey("sx_key")).isNotNull();
        fixture.state.deleteApiKey("sx_key");
        assertThat(other.apiKey("sx_key")).isNull();
        assertThat(fixture.state.apiKey("sx_key")).isNull();
    }

    @Test
    void 缓存有效期内的请求不再访问Valkey() {
        var state = fixture.state;
        state.session(会话);
        state.user(用户);
        int reads = fixture.backend.reads;
        for (int i = 0; i < 100; i++) { state.session(会话); state.user(用户); }
        assertThat(fixture.backend.reads).isEqualTo(reads);
    }

    @Test
    void Valkey宕机_没有本地缓存的会话一律401() {
        var cold = new AuthState(fixture.backend, fixture.properties, fixture.backend.clock::get);
        fixture.backend.down = true;
        assertThatThrownBy(() -> cold.session(会话)).isInstanceOf(AuthStateUnavailableException.class)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cold.user(用户)).isInstanceOf(AuthStateUnavailableException.class);
        assertThatThrownBy(() -> cold.apiKey("sx_key")).isInstanceOf(AuthStateUnavailableException.class);
    }

    @Test
    void Valkey宕机_本地缓存在六十秒内继续可用_之后401() {
        var state = fixture.state;
        state.session(会话);
        state.user(用户);
        fixture.backend.down = true;
        过去(Duration.ofSeconds(30)); // 已过 4 秒新鲜期，但仍在 60 秒容忍期内
        assertThat(state.session(会话)).isNotNull();
        assertThat(state.user(用户)).isNotNull();
        过去(Duration.ofSeconds(31));
        assertThatThrownBy(() -> state.session(会话)).isInstanceOf(AuthStateUnavailableException.class);
    }

    @Test
    void Valkey宕机时续期失败不影响缓存中的会话() {
        var state = fixture.state;
        fixture.properties.getSecurity().setSessionRenewInterval(Duration.ofSeconds(1));
        state.session(会话);
        过去(Duration.ofSeconds(2));
        fixture.backend.down = true;
        assertThat(state.session(会话)).isNotNull();
    }

    @Test
    void Valkey里没有会话_401而不是回退查库() {
        assertThat(fixture.state.session(99999L)).isNull();
        fixture.backend.reads = 0;
    }

    @Test
    void 本地缓存有上限且读到删除后的状态会清掉旧缓存() {
        var state = fixture.state;
        state.user(用户);
        fixture.backend.delete(用户键); // 绕过广播，模拟 Valkey 被直接清理
        过去(Duration.ofSeconds(5));
        assertThat(state.user(用户)).isNull();
    }
}
