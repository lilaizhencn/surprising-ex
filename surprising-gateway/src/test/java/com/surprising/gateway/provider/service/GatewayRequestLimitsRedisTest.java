package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.*;

import com.surprising.gateway.provider.config.GatewayLimitProperties;
import com.surprising.gateway.provider.config.GatewayLimitProperties.*;
import com.surprising.gateway.provider.controller.GatewayLimitErrors;
import com.surprising.product.api.ProductLine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "GATEWAY_LIMIT_TEST_REDIS_PORT", matches = "[0-9]+")
class GatewayRequestLimitsRedisTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    GatewayLimitProperties properties;
    GatewayRequestLimits first, second;

    @BeforeEach void setup() {
        var config = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("GATEWAY_LIMIT_TEST_REDIS_PORT")));
        factory = new LettuceConnectionFactory(config, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
        properties = new GatewayLimitProperties(); properties.setNamespace("gateway:test:" + UUID.randomUUID());
        first = new GatewayRequestLimits(redis, properties, new SimpleMeterRegistry());
        second = new GatewayRequestLimits(redis, properties, new SimpleMeterRegistry());
    }

    @AfterEach void cleanup() {
        if (redis != null) { var keys = redis.keys(properties.getNamespace() + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys); }
        if (factory != null) factory.destroy();
    }

    @Test void twoGatewaysAndMultipleKeysShareTheUserQuota() {
        properties.getUser().put(Group.READ, new Rule(1, 2));
        properties.getApiKey().put(Group.READ, new Rule(1, 10));
        first.checkUser(42, "key-a", Group.READ, ProductLine.SPOT, 1);
        second.checkUser(42, "key-b", Group.READ, ProductLine.LINEAR_PERPETUAL, 1);
        assertThatThrownBy(() -> first.checkUser(42, null, Group.READ, ProductLine.SPOT, 1))
                .isInstanceOfSatisfying(GatewayRequestLimits.Rejection.class,
                        failure -> assertThat(failure.reason()).isEqualTo(GatewayRequestLimits.Reason.RATE_LIMITED));
        second.checkUser(43, "other-user", Group.READ, ProductLine.SPOT, 1);
    }

    @Test void rejectionDoesNotPartiallyDebitAnotherBucketAndAllBucketsExpire() {
        properties.getUser().put(Group.READ, new Rule(1, 5));
        properties.getApiKey().put(Group.READ, new Rule(1, 1));
        first.checkUser(42, "small-key", Group.READ, null, 1);
        String user = properties.getNamespace() + ":{user:42}:READ:p1000";
        Object before = redis.opsForHash().get(user, "tokens");
        assertThatThrownBy(() -> second.checkUser(42, "small-key", Group.READ, null, 1))
                .isInstanceOf(GatewayRequestLimits.Rejection.class);
        assertThat(redis.opsForHash().get(user, "tokens")).isEqualTo(before);
        assertThat(redis.getExpire(user)).isBetween(1L, 6L);
    }

    @Test void simultaneousRequestsCannotOverspendABatchQuota() throws Exception {
        properties.getUser().put(Group.ORDER, new Rule(1, 10));
        properties.getApiKey().put(Group.ORDER, new Rule(1, 10));
        // Warm the transport without consuming this user's order allowance.
        first.checkUser(99, null, Group.READ, null, 1);
        var start = new CountDownLatch(1); var accepted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                try {
                    start.await(); second.checkUser(42, "bot", Group.ORDER, null, 10); accepted.incrementAndGet();
                } catch (GatewayRequestLimits.Rejection failure) {
                    assertThat(failure.reason()).isEqualTo(GatewayRequestLimits.Reason.RATE_LIMITED);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(accepted).hasValue(1);
    }

    @Test void cancellationsAndReceiptsHaveTheirOwnQuotaAndConcurrencyCapacity() throws Exception {
        properties.getUser().put(Group.ORDER, new Rule(1, 1));
        first.checkUser(42, null, Group.ORDER, ProductLine.SPOT, 1);
        assertThatThrownBy(() -> first.checkUser(42, null, Group.ORDER, ProductLine.SPOT, 1)).isInstanceOf(GatewayRequestLimits.Rejection.class);
        first.checkUser(42, null, Group.CANCEL, ProductLine.SPOT, 1);
        first.checkUser(42, null, Group.RECEIPT, ProductLine.SPOT, 1);
        properties.setConcurrentRequests(1); var limited = new GatewayRequestLimits(redis, properties, new SimpleMeterRegistry());
        var request = limited.admit(Group.READ);
        assertThatThrownBy(() -> limited.admit(Group.ORDER)).isInstanceOf(GatewayRequestLimits.Rejection.class);
        try (var cancel = limited.admit(Group.CANCEL); var receipt = limited.admit(Group.RECEIPT)) { assertThat(cancel).isNotNull(); assertThat(receipt).isNotNull(); }
        request.close(); request.close();
        try (var next = limited.admit(Group.ORDER)) {
            assertThatThrownBy(() -> limited.admit(Group.ORDER)).isInstanceOf(GatewayRequestLimits.Rejection.class);
        }
    }

    @Test void productBudgetsRemainSeparateWithinTheSharedUserTotal() {
        properties.getUser().put(Group.ORDER, new Rule(1, 10));
        properties.getProduct().put(Group.ORDER, new Rule(1, 1));
        first.checkUser(42, null, Group.ORDER, ProductLine.SPOT, 1);
        assertThatThrownBy(() -> first.checkUser(42, null, Group.ORDER, ProductLine.SPOT, 1)).isInstanceOf(GatewayRequestLimits.Rejection.class);
        second.checkUser(42, null, Group.ORDER, ProductLine.LINEAR_PERPETUAL, 1);
    }

    @Test void connectionLeasesWorkAcrossGatewaysAndExpiredMembersDoNotOccupyCapacity() {
        properties.setSocketsPerIp(1); properties.setSocketsPerUser(1);
        first.renewSocket("192.0.2.1", 42L, "first");
        assertThatThrownBy(() -> second.renewSocket("192.0.2.1", 43L, "second")).isInstanceOf(GatewayRequestLimits.Rejection.class);
        assertThatThrownBy(() -> second.renewSocket("192.0.2.2", 42L, "second")).isInstanceOf(GatewayRequestLimits.Rejection.class);
        first.releaseSocket("192.0.2.1", 42L, "first");
        second.renewSocket("192.0.2.2", 42L, "second");
        for (String key : List.of(properties.getNamespace() + ":{ip:192.0.2.2}:sockets", properties.getNamespace() + ":{user:42}:sockets"))
            for (String member : Objects.requireNonNull(redis.opsForZSet().range(key, 0, -1))) redis.opsForZSet().add(key, member, 1);
        first.renewSocket("192.0.2.2", 42L, "after-crash");
        assertThat(redis.opsForZSet().zCard(properties.getNamespace() + ":{user:42}:sockets")).isEqualTo(1L);
    }

    @Test void quotaResponsesHaveRetryAfterAndDoNotExposeActorKeys() {
        var rejection = new GatewayRequestLimits.Rejection(GatewayRequestLimits.Reason.RATE_LIMITED, "ORDER", 1201);
        var response = GatewayLimitErrors.response(rejection);
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody()).containsEntry("code", "RATE_LIMITED").containsEntry("retryAfterSeconds", 2L);
    }

    @Test void loginIdentifierBudgetUsesMinutesAndNormalizesCaseAndWhitespace() {
        for (int i = 0; i < 5; i++) first.checkLoginAccount(" Owner@Example.COM ");
        assertThatThrownBy(() -> second.checkLoginAccount("owner@example.com"))
                .isInstanceOfSatisfying(GatewayRequestLimits.Rejection.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(GatewayRequestLimits.Reason.RATE_LIMITED);
                    assertThat(failure.retryAfterSeconds()).isBetween(11L, 12L);
                });
        first.checkLoginAccount("different@example.com");
    }
}
