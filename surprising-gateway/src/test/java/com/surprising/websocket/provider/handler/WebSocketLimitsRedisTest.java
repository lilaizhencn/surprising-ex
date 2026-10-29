package com.surprising.websocket.provider.handler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

import com.surprising.gateway.provider.auth.port.CurrentAuthClient;
import com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.*;
import com.surprising.gateway.provider.config.GatewayLimitProperties.Rule;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.service.GatewayRequestLimits;
import com.surprising.websocket.provider.config.WebSocketProperties;
import com.surprising.websocket.provider.service.*;
import com.surprising.websocket.provider.session.ClientConnection;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "GATEWAY_LIMIT_TEST_REDIS_PORT", matches = "[0-9]+")
class WebSocketLimitsRedisTest {
    record Node(GatewayRequestLimits limits, SubscriptionRegistry registry, ClientWebSocketHandler handler) {}
    final String key = "sx_" + "a".repeat(24);
    final ObjectMapper json = new ObjectMapper();
    final GatewayLimitProperties policies = new GatewayLimitProperties();
    final GatewayApiKeyRepository rows = mock(GatewayApiKeyRepository.class);
    final AuthService auth = mock(AuthService.class);
    final List<Node> nodes = new ArrayList<>();
    final List<String> frames = new CopyOnWriteArrayList<>();
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    GatewayApiKeyService keys;

    @BeforeEach void setup() {
        policies.setNamespace("gateway:ws:" + UUID.randomUUID()); policies.setSocketsPerGateway(2);
        policies.setNewWebSocket(new Rule(100, 100));
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getenv("GATEWAY_LIMIT_TEST_REDIS_PORT"))));
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
        try (var connection = factory.getConnection()) { connection.ping(); }
        var properties = new GatewayProperties(); var totp = new TotpService(properties);
        keys = new GatewayApiKeyService(rows, auth, totp, null, properties, mock(CurrentAuthClient.class));
        when(rows.active(key)).thenReturn(Optional.of(new GatewayApiKeyRepository.ApiKeyRecord(UUID.randomUUID(), 42, key,
                totp.encryptSecret("secret"), "bot", "READ", "192.0.2.0/24", "ACTIVE", Instant.now(), null, null)));
    }

    @AfterEach void cleanup() throws Exception {
        for (var node : nodes) for (var connection : node.registry().connections()) node.registry().remove(connection.id());
        for (var node : nodes) await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var permits = new ArrayList<AutoCloseable>();
            try { for (int i = 0; i < policies.getSocketsPerGateway(); i++) permits.add(node.limits().admitSocket()); }
            finally { for (var permit : permits) permit.close(); }
        });
        var names = redis.keys(policies.getNamespace() + "*"); if (names != null && !names.isEmpty()) redis.delete(names);
        factory.destroy();
    }

    @Test void matchingLocalSessionIdsOnDifferentGatewaysCannotBypassTheIpConnectionLimit() throws Exception {
        policies.setSocketsPerIp(1); var first = node(); var second = node();
        var admitted = socket(first, "same-id", "192.0.2.7");
        var rejected = socket(second, "same-id", "192.0.2.7");
        assertThat(first.registry().activeConnectionCount()).isEqualTo(1);
        assertThat(second.registry().activeConnectionCount()).isZero();
        verify(rejected).close(argThat(status -> status.getCode() == 1013 && status.getReason().equals("WEBSOCKET_CONNECTION_LIMIT")));
        verify(admitted, never()).close(any());
        assertThat(redis.opsForZSet().zCard(policies.getNamespace() + ":{ip:192.0.2.7}:sockets")).isEqualTo(1);
    }

    @Test void theUserLimitAppliesAcrossIpsAndAnUnadmittedAuthenticationNeverGrantsAnIdentity() throws Exception {
        policies.setSocketsPerUser(1); var first = node(); var second = node();
        var admitted = socket(first, "same-id", "192.0.2.7");
        var rejected = socket(second, "same-id", "192.0.2.8");
        authenticate(first, admitted); authenticate(second, rejected);
        assertThat(first.registry().connection(admitted.getId()).authenticatedUserId()).isEqualTo(42);
        verify(rejected, timeout(2000)).close(argThat(status -> status.getCode() == 1013
                && status.getReason().equals("WEBSOCKET_CONNECTION_LIMIT")));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(second.registry().activeConnectionCount()).isZero());
        assertThat(redis.opsForZSet().zCard(policies.getNamespace() + ":{user:42}:sockets")).isEqualTo(1);
    }

    @Test void theNodeCapRejectsBeforeCreatingConnectionWorkers() throws Exception {
        policies.setSocketsPerGateway(1); var node = node();
        socket(node, "first", "192.0.2.7"); var rejected = socket(node, "second", "192.0.2.8");
        verify(rejected).close(argThat(status -> status.getCode() == 1013 && status.getReason().equals("GATEWAY_BUSY")));
        assertThat(node.registry().activeConnectionCount()).isEqualTo(1);
    }

    @Test void normalSixProductPrivateSubscriptionBootstrapFitsTheDefaultBudget() throws Exception {
        var node = node(); var socket = socket(node, "six-products", "192.0.2.7"); authenticate(node, socket);
        for (var product : com.surprising.product.api.ProductLine.values())
            for (String channel : List.of("accountState", "orders", "triggerOrders", "positions", "positionRisk", "executionReports"))
                command(node, socket, Map.of("op", "subscribe", "productLine", product.name(), "channel", channel));
        assertThat(node.registry().connection(socket.getId()).isOpen()).isTrue();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(frames.stream().filter(frame -> frame.contains("\"op\":\"subscribed\""))).hasSize(36));
        verify(socket, never()).close(any());
    }

    @Test void inboundFloodsAreRejectedBeforePingProcessingAndRepeatedViolationsCloseTheSocket() throws Exception {
        policies.setSocketMessages(new Rule(1, 2)); var node = node(); var socket = socket(node, "one", "192.0.2.7");
        command(node, socket, Map.of("op", "ping", "id", "first"));
        command(node, socket, Map.of("op", "ping", "id", "second"));
        for (int i = 0; i < 3; i++) command(node, socket, Map.of("op", "ping", "id", "blocked-" + i));
        verify(socket, timeout(2000)).close(argThat(status -> status.getCode() == 1013 && status.getReason().equals("RATE_LIMITED")));
        assertThat(frames).noneMatch(value -> value.contains("blocked-"));
    }

    @Test void anExpiredLeaseStopsPublicationAndReleasesBothNodeAndIpCapacity() throws Exception {
        policies.setSocketsPerGateway(1); policies.setSocketsPerIp(1); var node = node();
        var socket = socket(node, "one", "192.0.2.7"); var connection = node.registry().connection(socket.getId());
        connection.renewLimits(System.nanoTime() - 1);
        assertThat(connection.send("never-after-expiry")).isFalse();
        verify(socket, timeout(2000)).close(argThat(status -> status.getCode() == 1013));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            try (var permit = node.limits().admitSocket()) { assertThat(permit).isNotNull(); }
        });
        var next = socket(node, "two", "192.0.2.7");
        assertThat(node.registry().connection(next.getId()).isOpen()).isTrue();
        assertThat(frames).doesNotContain("never-after-expiry");
    }

    private Node node() {
        var limits = new GatewayRequestLimits(redis, policies, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var properties = new WebSocketProperties(); var registry = new SubscriptionRegistry(json, properties);
        var handler = new ClientWebSocketHandler(json, registry, properties, new WebSocketAuthenticator(auth, keys),
                mock(GatewayProductServices.class), limits);
        var node = new Node(limits, registry, handler); nodes.add(node); return node;
    }
    private WebSocketSession socket(Node node, String id, String ip) throws Exception {
        var socket = mock(WebSocketSession.class); var open = new AtomicBoolean(true);
        when(socket.getId()).thenReturn(id); when(socket.isOpen()).thenAnswer(call -> open.get());
        when(socket.getAttributes()).thenReturn(Map.of(ClientConnection.CLIENT_IP, ip));
        doAnswer(call -> { open.set(false); return null; }).when(socket).close(any());
        doAnswer(call -> { frames.add(((TextMessage) call.getArgument(0)).getPayload()); return null; }).when(socket).sendMessage(any());
        node.handler().afterConnectionEstablished(socket); return socket;
    }
    private void authenticate(Node node, WebSocketSession socket) {
        command(node, socket, Map.of("op", "apiChallenge"));
        String challenge = node.registry().connection(socket.getId()).apiChallenge().challenge(); long now = System.currentTimeMillis();
        command(node, socket, Map.of("op", "authenticateApiKey", "apiKey", key, "timestamp", now,
                "signature", keys.sign("secret", "WS\n/ws/v1\n" + challenge + "\n" + key + "\n" + now)));
    }
    private void command(Node node, WebSocketSession socket, Map<String, ?> command) {
        node.handler().handleTextMessage(socket, new TextMessage(json.writeValueAsString(command)));
    }
}
