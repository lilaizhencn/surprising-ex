package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cChatRealtimePostgresTest {
    private C2cHttpFixture fixture;
    private C2cChatRealtime socket;
    private final java.util.Map<String,com.surprising.websocket.provider.service.ClientConnection> wires=new java.util.HashMap<>();
    private final List<String> frames=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        fixture=new C2cHttpFixture();fixture.db.jdbc.update("UPDATE c2c_profiles SET receive_strangers=true");
        socket=new C2cChatRealtime(fixture.json,fixture.bean(C2cAccess.class),fixture.bean(C2cConversations.class),fixture.bean(C2cMessages.class),2);
    }
    @AfterEach void cleanup() {if(socket!=null)socket.stop();for(var wire:wires.values())wire.close();if(fixture!=null)fixture.close();}
    private WebSocketSession session(String suffix) throws Exception {
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn(suffix);when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("ws://127.0.0.1/ws/v1"));
        var headers=new HttpHeaders();headers.set("X-User-Id","9");when(session.getHandshakeHeaders()).thenReturn(headers);
        doAnswer(call->{frames.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(session).sendMessage(any());
        var wire=new com.surprising.websocket.provider.service.ClientConnection(session,null,4,java.time.Duration.ofSeconds(2));
        wires.put(suffix,wire);return session;
    }
    private void command(WebSocketSession session,Map<String,?> command) {
        var wire=wires.get(session.getId());
        if("authenticate".equals(command.get("op"))) {
            String token=(String)command.get("token");long id=new com.surprising.websocket.provider.service.WebSocketJwtAuthenticator(fixture.auth).authenticate(token);
            wire.authenticate(id,token);wire.send("{\"op\":\"authenticated\",\"userId\":\""+id+"\"}");
        } else {
            socket.handle(wire,new C2cChatRealtime.Command((String)command.get("op"),null,(String)command.get("conversationId"),
                    (String)command.get("after"),(String)command.get("afterEvent")));
        }
    }
    private void awaitFrame(String value) throws Exception {
        long end=System.nanoTime()+2_000_000_000L;
        while(System.nanoTime()<end && frames.stream().noneMatch(frame->frame.contains(value)))Thread.sleep(10);
        assertThat(frames.stream().anyMatch(frame->frame.contains(value))).withFailMessage("Expected frame containing %s, received %s",value,frames).isTrue();
    }
    @Test void onlyTradingAuthenticationCanAuthorizeTheSocketAndHistoryReplaysFromTheOriginalCursor() throws Exception {
        var conversation=fixture.bean(C2cConversations.class).start(12,null,11L);UUID id=UUID.fromString((String)conversation.get("conversationId"));
        var original=fixture.bean(C2cMessages.class).send(11,id,UUID.randomUUID(),"断线前的消息",null);
        var session=session("first");command(session,Map.of("op","authenticate","token","qa-12"));
        command(session,Map.of("op","c2c.watch","conversationId",id.toString(),"after","0","afterEvent","0"));
        awaitFrame("断线前的消息");assertThat(frames.stream().anyMatch(frame->frame.contains("\"userId\":\"12\""))).isTrue();
        socket.closed(session.getId());wires.remove(session.getId()).close();frames.clear();
        var second=fixture.bean(C2cMessages.class).send(11,id,UUID.randomUUID(),"断线后的消息",null);
        var resumed=session("resumed");command(resumed,Map.of("op","authenticate","token","qa-12"));
        command(resumed,Map.of("op","c2c.watch","conversationId",id.toString(),"after",original.get("messageId"),"afterEvent","0"));
        awaitFrame("断线后的消息");assertThat(frames.stream().noneMatch(frame->frame.contains("断线前的消息"))).isTrue();
        assertThat(second.get("messageId")).isNotEqualTo(original.get("messageId"));
    }
    @Test void committedMessagesFromAnotherNodeAndReadReceiptsAreObservedFromSharedStorage() throws Exception {
        UUID id=UUID.fromString((String)fixture.bean(C2cConversations.class).start(12,null,11L).get("conversationId"));
        var session=session("buyer");command(session,Map.of("op","authenticate","token","qa-12"));
        command(session,Map.of("op","c2c.watch","conversationId",id.toString()));awaitFrame("messages");
        var sent=fixture.bean(C2cMessages.class).send(11,id,UUID.randomUUID(),"另一进程写入的消息",null);
        socket.poll();awaitFrame("另一进程写入的消息");
        frames.clear();fixture.bean(C2cMessages.class).markRead(11,id,Long.parseLong((String)sent.get("messageId")),0);
        socket.poll();awaitFrame("\"revision\":\"1\"");
    }
    @Test void logoutOrRevocationClosesTheConnectionBeforeAnyFurtherPrivateDelivery() throws Exception {
        var session=session("revoked");command(session,Map.of("op","authenticate","token","qa-12"));awaitFrame("authenticated");
        command(session,Map.of("op","c2c.inbox"));
        doThrow(new IllegalArgumentException("revoked session")).when(fixture.auth).authenticateBearer("Bearer qa-12");
        socket.poll();verify(session,timeout(1000)).close(CloseStatus.POLICY_VIOLATION);
        verify(fixture.auth,times(3)).authenticateBearer("Bearer qa-12");
    }
    @Test void forgedHandshakeIdentityAndForeignConversationNeverGrantAccess() throws Exception {
        UUID id=UUID.fromString((String)fixture.bean(C2cConversations.class).start(12,null,11L).get("conversationId"));
        var unauthenticated=session("without-token");assertThatThrownBy(()->command(unauthenticated,Map.of("op","c2c.watch","conversationId",id.toString()))).isInstanceOf(C2cProblem.class);
        verify(unauthenticated,timeout(1000)).close(CloseStatus.POLICY_VIOLATION);
        var stranger=session("stranger");command(stranger,Map.of("op","authenticate","token","qa-13"));
        assertThatThrownBy(()->command(stranger,Map.of("op","c2c.watch","conversationId",id.toString()))).isInstanceOf(C2cProblem.class);
        assertThat(frames.stream().noneMatch(frame->frame.contains("\"messages\""))).isTrue();
    }
    @Test void chatSubscriptionCapacityIsBoundedWithoutClosingMarketConnections() throws Exception {
        for(String id:List.of("one","two")) {var session=session(id);command(session,Map.of("op","authenticate","token","qa-12"));command(session,Map.of("op","c2c.inbox"));}
        var third=session("three");command(third,Map.of("op","authenticate","token","qa-12"));
        assertThatThrownBy(()->command(third,Map.of("op","c2c.inbox"))).isInstanceOf(C2cProblem.class);
        verify(third,never()).close(any(CloseStatus.class));
    }
    @Test void anAuthenticatedConnectionCannotSwitchTradingUsers() throws Exception {
        var session=session("one-user");command(session,Map.of("op","authenticate","token","qa-12"));awaitFrame("authenticated");
        assertThatThrownBy(()->command(session,Map.of("op","authenticate","token","qa-13"))).isInstanceOf(IllegalArgumentException.class);
    }
}
