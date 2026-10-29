package com.surprising.websocket.provider.handler;

import com.surprising.websocket.provider.service.WebSocketAuthenticator;
import com.surprising.websocket.provider.service.SubscriptionRegistry;
import com.surprising.websocket.provider.session.ClientConnection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.c2c.websocket.C2cChatRealtime;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.model.*;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;

class ClientWebSocketHandlerTest {
    private final ObjectMapper json=new ObjectMapper();
    private final WebSocketProperties properties=new WebSocketProperties();
    private final SubscriptionRegistry registry=new SubscriptionRegistry(json,properties);
    private final AuthService auth=mock(AuthService.class);
    private final C2cChatRealtime chat=mock(C2cChatRealtime.class);
    private final ClientWebSocketHandler handler=new ClientWebSocketHandler(json,registry,properties,
            new WebSocketAuthenticator(auth, mock(com.surprising.gateway.provider.auth.service.GatewayApiKeyService.class)),mock(GatewayProductServices.class), disabledLimits());
    private final com.surprising.websocket.provider.service.WebSocketSessionAuthorization authorization = new com.surprising.websocket.provider.service.WebSocketSessionAuthorization(registry, new WebSocketAuthenticator(auth, mock(com.surprising.gateway.provider.auth.service.GatewayApiKeyService.class)), java.util.Optional.of(chat));
    private final com.surprising.websocket.provider.task.WebSocketSessionAuthorizationTask authorizationTask = new com.surprising.websocket.provider.task.WebSocketSessionAuthorizationTask(registry, authorization, new com.surprising.websocket.provider.config.WebSocketAuthorizationConfiguration().websocketAuthorizationChecks(properties));
    private final List<String> frames=new CopyOnWriteArrayList<>();
    private final List<WebSocketSession> sessions=new ArrayList<>();
    @BeforeEach void setup() {
        handler.c2c(chat);
        when(auth.authenticateBearer("Bearer qa-7")).thenReturn(new JwtPrincipal(7,"qa","NORMAL",List.of("USER"),Instant.now().plusSeconds(60)));
    }
    @AfterEach void cleanup() {authorizationTask.close();for(var session:sessions)registry.remove(session.getId());}
    private WebSocketSession session() throws Exception {
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn(UUID.randomUUID().toString());when(session.isOpen()).thenReturn(true);
        var headers=new HttpHeaders();headers.set("X-User-Id","99");when(session.getHandshakeHeaders()).thenReturn(headers);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/v1?userId=99"));
        doAnswer(call->{frames.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(session).sendMessage(any());
        handler.afterConnectionEstablished(session);sessions.add(session);return session;
    }
    private void command(WebSocketSession session,Map<String,?> value) {handler.handleTextMessage(session,new TextMessage(json.writeValueAsString(value)));}
    private void awaitFrame(String value) throws Exception {
        long until=System.nanoTime()+2_000_000_000L;
        while(System.nanoTime()<until && frames.stream().noneMatch(s->s.contains(value)))Thread.sleep(10);
        assertThat(frames.stream().anyMatch(s->s.contains(value))).isTrue();
    }
    @Test void handshakeHeadersAndQueryParametersNeverAuthenticateAUser() throws Exception {
        var session=session();assertThat(registry.connection(session.getId()).authenticatedUserId()).isNull();
        command(session,Map.of("op","subscribe","channel","orders","productLine","SPOT"));
        awaitFrame("private websocket channel requires authenticated user");
    }
    @Test void marketChatAndHeartbeatUseTheExactSameAuthenticatedConnectionAndQueue() throws Exception {
        var session=session();var connection=registry.connection(session.getId());
        doAnswer(call->{
            assertThat(call.getArgument(0,ClientConnection.class)).isSameAs(connection);
            connection.send("{\"op\":\"c2c.changed\",\"channel\":\"c2c.inbox\"}");return null;
        }).when(chat).handle(any(),any());
        command(session,Map.of("op","authenticate","token","qa-7"));
        command(session,Map.of("op","subscribe","channel","trades","instrumentId","1001","productLine","SPOT"));
        command(session,Map.of("op","c2c.inbox"));
        registry.publish(new SubscriptionTopic(WsChannel.TRADES,"1001",null,null,ProductLine.SPOT),Map.of("price","100"),Instant.now());
        command(session,Map.of("op","ping","id","shared-heartbeat"));
        awaitFrame("c2c.inbox");awaitFrame("\"channel\":\"trades\"");awaitFrame("pong");
        assertThat(connection.authenticatedUserId()).isEqualTo(7);verify(auth,times(1)).authenticateBearer("Bearer qa-7");
        verify(session,never()).close(any(CloseStatus.class));
    }
    @Test void privateSubscriptionsRecheckTheTradingSessionAndRejectAnotherUser() throws Exception {
        var session=session();command(session,Map.of("op","authenticate","token","qa-7"));
        command(session,Map.of("op","subscribe","channel","orders","productLine","SPOT","userId","8"));
        awaitFrame("requested userId does not match authenticated user");
        doThrow(new IllegalArgumentException("access session is no longer active")).when(auth).authenticateBearer("Bearer qa-7");
        command(session,Map.of("op","subscribe","channel","orders","productLine","SPOT"));
        awaitFrame("access session is no longer active");
    }
    @Test void revokedSessionIsClosedAndRoutesRemovedWhileAnotherSessionOfSameUserSurvives() throws Exception {
        when(auth.authenticateBearer("Bearer qa-other"))
                .thenReturn(new JwtPrincipal(7,"qa","NORMAL",List.of("USER"),Instant.now().plusSeconds(60)));
        var revoked = session();
        var healthy = session();
        command(revoked, Map.of("op", "authenticate", "token", "qa-7"));
        command(healthy, Map.of("op", "authenticate", "token", "qa-other"));
        command(revoked, Map.of("op", "subscribe", "channel", "orders", "productLine", "SPOT"));
        command(healthy, Map.of("op", "subscribe", "channel", "orders", "productLine", "SPOT"));
        var topic = new SubscriptionTopic(WsChannel.ORDERS, "*", null, 7L, ProductLine.SPOT);
        assertThat(registry.subscriberCount(topic)).isEqualTo(2);
        doThrow(new IllegalArgumentException("access session is no longer active"))
                .when(auth).authenticateBearer("Bearer qa-7");
        Thread.sleep(3100);
        authorizationTask.revalidateSessions();
        verify(revoked, timeout(1000)).close(argThat(status -> status.getCode() == 1008));
        verify(healthy, never()).close(any(CloseStatus.class));
        assertThat(registry.subscriberCount(topic)).isEqualTo(1);
        org.mockito.Mockito.verify(revoked, timeout(1000)).close(any(CloseStatus.class));
        long cleanupDeadline = System.nanoTime() + 1_000_000_000L;
        while (registry.activeConnectionCount() > 1 && System.nanoTime() < cleanupDeadline) Thread.sleep(5);
        assertThat(registry.activeConnectionCount()).isEqualTo(1);
        clearInvocations(revoked);
        registry.publish(topic, "private-after-revocation", Instant.now());
        awaitFrame("private-after-revocation");
        verify(revoked, never()).sendMessage(argThat(message -> message.getPayload().toString().contains("private-after-revocation")));

    }

    @Test void expiredTokenCannotQueueMorePrivateMessagesEvenBeforeScheduledRecheck() throws Exception {
        var session = session();
        command(session, Map.of("op", "authenticate", "token", "qa-7"));
        command(session, Map.of("op", "subscribe", "channel", "orders", "productLine", "SPOT"));
        awaitFrame("subscribed");
        var connection = registry.connection(session.getId());
        connection.renewAuthorization("qa-7", Instant.now().minusMillis(1));
        var topic = new SubscriptionTopic(WsChannel.ORDERS, "*", null, 7L, ProductLine.SPOT);
        registry.publish(topic, "never-deliver-after-expiry", Instant.now());
        verify(session, timeout(1000)).close(argThat(status -> status.getCode() == 1008));
        assertThat(registry.subscriberCount(topic)).isZero();
        assertThat(connection.queuedMessages()).isZero();
        assertThat(frames).noneMatch(frame -> frame.contains("never-deliver-after-expiry"));
    }

    @Test void idleExpiredConnectionIsClosedAndFrozenAccountFailsThePeriodicCheck() throws Exception {
        var expired = session();
        command(expired, Map.of("op", "authenticate", "token", "qa-7"));
        registry.connection(expired.getId()).renewAuthorization("qa-7", Instant.now().minusMillis(1));
        authorizationTask.revalidateSessions();
        verify(expired, timeout(1000)).close(argThat(status -> status.getCode() == 1008));
        var frozen = session();
        command(frozen, Map.of("op", "authenticate", "token", "qa-7"));
        command(frozen, Map.of("op", "subscribe", "channel", "orders", "productLine", "SPOT"));
        doThrow(new IllegalStateException("user is not active")).when(auth).authenticateBearer("Bearer qa-7");
        Thread.sleep(3100);
        authorizationTask.revalidateSessions();
        verify(frozen, timeout(1000)).close(argThat(status -> status.getCode() == 1008));
        long cleanupDeadline = System.nanoTime() + 1_000_000_000L;
        while (registry.activeConnectionCount() > 0 && System.nanoTime() < cleanupDeadline) Thread.sleep(5);
        assertThat(registry.activeConnectionCount()).isZero();
    }

    @Test void disconnectCleansChatSubscriptionsAlongWithMarketSubscriptions() throws Exception {
        var session=session();handler.afterConnectionClosed(session,CloseStatus.NORMAL);
        verify(chat).closed(session.getId());assertThatThrownBy(()->registry.connection(session.getId())).isInstanceOf(IllegalStateException.class);
    }
    @Test void aChatErrorDoesNotBecomeAMarketConnectionError() throws Exception {
        var session=session();command(session,Map.of("op","authenticate","token","qa-7"));
        doThrow(new IllegalArgumentException("会话无权访问")).when(chat).handle(any(),any());
        command(session,Map.of("op","c2c.watch","conversationId",UUID.randomUUID().toString()));
        command(session,Map.of("op","ping","id","still-live"));
        awaitFrame("c2c.error");awaitFrame("pong");
        assertThat(frames.stream().noneMatch(s->s.contains("\"op\":\"error\""))).isTrue();
        verify(session,never()).close(any(CloseStatus.class));
    }
    private static com.surprising.gateway.provider.service.GatewayRequestLimits disabledLimits() {
        var properties = new com.surprising.gateway.provider.config.GatewayLimitProperties();
        properties.setEnabled(false);
        return new com.surprising.gateway.provider.service.GatewayRequestLimits(null, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

}
