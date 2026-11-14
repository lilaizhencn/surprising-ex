package com.surprising.websocket.provider.handler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

import com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.websocket.provider.config.WebSocketProperties;
import com.surprising.websocket.provider.service.*;
import com.surprising.websocket.provider.session.ClientConnection;
import com.surprising.websocket.provider.session.WsTransportSession;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class WebSocketApiKeyAuthenticationTest {
    final com.surprising.gateway.provider.auth.state.AuthStateFixture fixture = new com.surprising.gateway.provider.auth.state.AuthStateFixture();
    final String key = "sx_" + "a".repeat(24);
    final ObjectMapper json = new ObjectMapper();
    final TotpService totp = new TotpService(new GatewayProperties());
    final GatewayApiKeyRepository repository = mock(GatewayApiKeyRepository.class);
    final AuthService auth = mock(AuthService.class);
    final GatewayApiKeyService keys = new GatewayApiKeyService(repository, auth, totp, null, new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.port.CurrentAuthClient.class), fixture.state);
    final WebSocketProperties properties = new WebSocketProperties();
    final SubscriptionRegistry registry = new SubscriptionRegistry(json, properties);
    final WebSocketAuthenticator authenticator = new WebSocketAuthenticator(auth, keys);
    final ClientWebSocketHandler handler = new ClientWebSocketHandler(json, registry, properties,
            authenticator, mock(GatewayProductServices.class), disabledLimits());
    final WebSocketSessionAuthorization authorization = new WebSocketSessionAuthorization(registry, authenticator, Optional.empty());
    final List<WsTransportSession> sessions = new ArrayList<>();
    final List<String> frames = new CopyOnWriteArrayList<>();

    @BeforeEach void seedKey() { stored("203.0.113.7"); }
    @AfterEach void close() { for (var session : sessions) registry.remove(session.id()); }

    @Test void aConnectionBoundProofAuthenticatesPrivateSubscriptionsAcrossProductsButNeverAnotherUser() throws Exception {
        var session = session();
        command(session, proof(session));
        var connection = registry.connection(session.id());
        assertThat(connection.authenticatedUserId()).isEqualTo(42L);
        for (String product : List.of("SPOT", "LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"))
            command(session, Map.of("op", "subscribe", "channel", "orders", "productLine", product));
        assertThat(registry.totalSubscriptionCount()).isEqualTo(6);
        command(session, Map.of("op", "subscribe", "channel", "orders", "productLine", "SPOT", "userId", 99));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(frames).anyMatch(x -> x.contains("requested userId does not match authenticated user")));
        assertThat(registry.totalSubscriptionCount()).isEqualTo(6);
        verify(auth, never()).authenticateBearer(any());
    }

    @Test void capturedProofCannotBeReplayedOnAnotherConnectionOrReusedOnTheSameConnection() throws Exception {
        var first = session(); var second = session();
        var captured = proof(first);
        command(second, Map.of("op", "apiChallenge", "id", "second"));
        command(second, captured);
        assertThat(registry.connection(second.id()).authenticatedUserId()).isNull();
        command(first, captured);
        assertThat(registry.connection(first.id()).authenticatedUserId()).isEqualTo(42L);
        clearInvocations(repository);
        command(first, captured);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest @ValueSource(strings = {"revoked", "ip-changed", "frozen", "valkey-unavailable"})
    void revalidationClosesTheConnectionAndRemovesSubscriptionsWhenTheGrantIsLost(String change) throws Exception {
        var session = session(); command(session, proof(session));
        command(session, Map.of("op", "subscribe", "channel", "orders", "productLine", "SPOT"));
        assertThat(registry.totalSubscriptionCount()).isEqualTo(1);
        switch (change) {
            case "revoked" -> fixture.state.deleteApiKey(key);
            case "ip-changed" -> stored("198.51.100.1");
            case "frozen" -> doThrow(new IllegalStateException("user is not active"))
                    .when(auth).requireApiKeyAccess(42L, "203.0.113.7", "READ");
            default -> { fixture.backend.down = true; fixture.backend.clock.addAndGet(120_000); }
        }
        authorization.revalidate(registry.connection(session.id()));
        verify(session, timeout(2000)).close(argThat(status -> status.code() == 1008));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(registry.totalSubscriptionCount()).isZero());
    }

    @Test void anExpiredChallengeCannotAuthenticateAndApiKeysCannotSendChatCommands() throws Exception {
        var session = session(); var proof = proof(session);
        var connection = registry.connection(session.id());
        var current = connection.apiChallenge();
        org.springframework.test.util.ReflectionTestUtils.setField(connection, "apiChallenge",
                new ClientConnection.ApiChallenge(current.challenge(), Instant.now().minusSeconds(1)));
        command(session, proof);
        assertThat(connection.authenticatedUserId()).isNull();
        command(session, proof(session));
        command(session, Map.of("op", "c2c.inbox"));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(frames).anyMatch(x -> x.contains("API key sessions do not support C2C commands")));
    }

    private void stored(String ip) {
        fixture.putApiKey(new GatewayApiKeyRepository.ApiKeyRecord(
                UUID.randomUUID(), 42L, key, totp.encryptSecret("secret"), "bot", "READ", ip, "ACTIVE", Instant.now(), null, null));
    }

    private Map<String, Object> proof(WsTransportSession session) {
        command(session, Map.of("op", "apiChallenge", "id", "challenge"));
        String challenge = registry.connection(session.id()).apiChallenge().challenge();
        long now = System.currentTimeMillis();
        return Map.of("op", "authenticateApiKey", "id", "key-auth", "apiKey", key, "timestamp", now,
                "signature", keys.sign("secret", "WS\n/ws/v1\n" + challenge + "\n" + key + "\n" + now));
    }

    private WsTransportSession session() throws Exception {
        var session = mock(WsTransportSession.class);
        when(session.id()).thenReturn(UUID.randomUUID().toString()); when(session.isOpen()).thenReturn(true);
        when(session.clientIp()).thenReturn("203.0.113.7");
        when(session.sendText(anyString())).thenAnswer(call -> { frames.add(call.getArgument(0)); return true; });
        when(session.sendBatch(anyList())).thenAnswer(call -> { frames.addAll(call.getArgument(0)); return true; });
        when(session.sendFrames(anyList(), anyList())).thenAnswer(call -> { frames.addAll(call.getArgument(1)); return true; });
        handler.afterConnectionEstablished(session); sessions.add(session); return session;
    }

    private void command(WsTransportSession session, Map<String, ?> body) {
        handler.handleTextMessage(session, json.writeValueAsString(body));
    }
    private static com.surprising.gateway.provider.service.GatewayRequestLimits disabledLimits() {
        var properties = new com.surprising.gateway.provider.config.GatewayLimitProperties();
        properties.setEnabled(false);
        return new com.surprising.gateway.provider.service.GatewayRequestLimits(null, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

}
