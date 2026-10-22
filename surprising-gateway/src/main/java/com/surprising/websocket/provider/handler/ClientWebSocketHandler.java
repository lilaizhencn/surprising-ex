package com.surprising.websocket.provider.handler;

import com.surprising.websocket.provider.service.RealtimeWebSocketBridge;
import com.surprising.websocket.provider.service.WebSocketJwtAuthenticator;
import com.surprising.websocket.provider.service.SubscriptionRegistry;
import com.surprising.websocket.provider.session.ClientConnection;

import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsClientCommand;
import com.surprising.websocket.api.model.WsServerMessage;
import com.surprising.websocket.provider.config.WebSocketProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
public class ClientWebSocketHandler extends TextWebSocketHandler {
    private final java.util.concurrent.atomic.AtomicLong nextFailureLog = new java.util.concurrent.atomic.AtomicLong();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClientWebSocketHandler.class);

    private com.surprising.gateway.c2c.websocket.C2cChatRealtime c2c;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void c2c(com.surprising.gateway.c2c.websocket.C2cChatRealtime chat) {this.c2c=chat;}
    private RealtimeWebSocketBridge realtime;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void realtime(RealtimeWebSocketBridge bridge) {this.realtime=bridge;}
    private final ObjectMapper objectMapper;
    private final SubscriptionRegistry registry;
    private final WebSocketProperties properties;
    private final WebSocketJwtAuthenticator jwtAuthenticator;
    private final com.surprising.gateway.provider.product.GatewayProductServices products;

    public ClientWebSocketHandler(ObjectMapper objectMapper,
                                  SubscriptionRegistry registry,
                                  WebSocketProperties properties,
                                  WebSocketJwtAuthenticator jwtAuthenticator,
                                  com.surprising.gateway.provider.product.GatewayProductServices products) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.properties = properties;
        this.jwtAuthenticator = jwtAuthenticator;
        this.products = products;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.add(new ClientConnection(session, null,
                properties.getSession().getOutboundQueueCapacity(),
                properties.getSession().getSendTimeout()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientConnection connection = connection(session);
        String operation="";
        try {
            var envelope=objectMapper.readTree(message.getPayload());
            if(!envelope.isObject()) throw new IllegalArgumentException("invalid websocket command");
            operation=envelope.path("op").asString("");
            if(operation.startsWith("c2c.")) {
                if(c2c==null) throw new IllegalArgumentException("C2C is not available");
                c2c.handle(connection,objectMapper.treeToValue(envelope,com.surprising.gateway.c2c.websocket.C2cChatRealtime.Command.class));
                return;
            }
            WsClientCommand command = objectMapper.treeToValue(envelope, WsClientCommand.class);
            String op = command.op() == null ? "" : command.op().trim().toLowerCase();
            log.debug("ws.command.start session={} op={}", session.getId(), op);
            switch (op) {
                case "authenticate" -> authenticate(connection, command);
                case "subscribe" -> subscribe(connection, command);
                case "unsubscribe" -> unsubscribe(connection, command);
                case "ping" -> connection.send(objectMapper.writeValueAsString(WsServerMessage.pong(command.id())));
                default -> throw new IllegalArgumentException("unsupported websocket op: " + command.op());
            }
            log.debug("ws.command.end session={} op={} result=OK", session.getId(), op);
        } catch (Exception ex) {
            logFailure("ws.command.failed", session.getId(), ex);
            if(operation.startsWith("c2c.")) {
                String error=(ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) && ex.getMessage()!=null
                        ?ex.getMessage():"聊天请求未完成，请稍后重试";
                connection.send(objectMapper.writeValueAsString(java.util.Map.of("op","c2c.error","channel","c2c.messages","message",error)));
            } else connection.send(objectMapper.writeValueAsString(WsServerMessage.error(null, ex.getMessage())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if(c2c!=null)c2c.closed(session.getId());
        registry.remove(session.getId());
    }

    private void subscribe(ClientConnection connection, WsClientCommand command) {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(command, connection.authenticatedUserId());
        if(topic.userId()!=null && jwtAuthenticator.authenticate(connection.accessToken())!=topic.userId())
            throw new IllegalArgumentException("websocket identity mismatch");
        products.requireEnabled(topic.productLine());
        if (topic.channel() == com.surprising.websocket.api.model.WsChannel.OPEN_INTEREST) {
            if (topic.productLine() == com.surprising.product.api.ProductLine.SPOT)
                throw new IllegalArgumentException("open interest requires a derivatives product");
        }
        registry.subscribe(connection, topic);
        connection.send(objectMapper.writeValueAsString(WsServerMessage.ack(command.id(), topic)));
        if(realtime!=null && topic.userId()!=null) {
            var view=realtime.snapshot(topic.productLine(),topic.userId());
            connection.send(objectMapper.writeValueAsString(new WsServerMessage("snapshot",command.id(),topic.channel().code(),
                    topic.instrumentId(),topic.period(),topic.userId(),topic.productLine(),view,null,java.time.Instant.now())));
        }
    }

    private void authenticate(ClientConnection connection, WsClientCommand command) {
        var principal = jwtAuthenticator.principal(command.token());
        long userId = principal.userId();
        connection.authenticate(userId, command.token(), principal.expiresAt());
        connection.send(objectMapper.writeValueAsString(WsServerMessage.authenticated(command.id(), userId)));
    }

    private void unsubscribe(ClientConnection connection, WsClientCommand command) {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(command, connection.authenticatedUserId());
        registry.unsubscribe(connection, topic);
        connection.send(objectMapper.writeValueAsString(WsServerMessage.unack(command.id(), topic)));
    }

    private void logFailure(String operation, String sessionId, Exception failure) {
        long now = System.nanoTime();
        long next = nextFailureLog.get();
        if (now >= next && nextFailureLog.compareAndSet(next, now + 5_000_000_000L))
            log.warn("{} session={} error={}", operation, sessionId, failure.getClass().getSimpleName());
    }

    private ClientConnection connection(WebSocketSession session) {
        return registry.connection(session.getId());
    }

}
