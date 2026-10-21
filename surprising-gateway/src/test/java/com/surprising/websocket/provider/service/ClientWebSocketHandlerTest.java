package com.surprising.websocket.provider.service;

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
            new WebSocketJwtAuthenticator(auth),mock(GatewayProductServices.class));
    private final List<String> frames=new CopyOnWriteArrayList<>();
    private final List<WebSocketSession> sessions=new ArrayList<>();
    @BeforeEach void setup() {
        handler.c2c(chat);
        when(auth.authenticateBearer("Bearer qa-7")).thenReturn(new JwtPrincipal(7,"qa","NORMAL",List.of("USER"),Instant.now().plusSeconds(60)));
    }
    @AfterEach void cleanup() {for(var session:sessions)registry.remove(session.getId());}
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
}
