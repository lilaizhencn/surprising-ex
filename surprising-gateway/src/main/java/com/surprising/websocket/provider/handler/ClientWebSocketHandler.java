package com.surprising.websocket.provider.handler;

import com.surprising.websocket.provider.service.RealtimeWebSocketBridge;
import com.surprising.websocket.provider.service.WebSocketAuthenticator;
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
    private final WebSocketAuthenticator authenticator;
    private final com.surprising.gateway.provider.product.GatewayProductServices products;
    private final com.surprising.gateway.provider.service.GatewayRequestLimits limits;

    public ClientWebSocketHandler(ObjectMapper objectMapper,
                                  SubscriptionRegistry registry,
                                  WebSocketProperties properties,
                                  WebSocketAuthenticator authenticator,
                                  com.surprising.gateway.provider.product.GatewayProductServices products,
                                  com.surprising.gateway.provider.service.GatewayRequestLimits limits) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.properties = properties;
        this.authenticator = authenticator;
        this.products = products;
        this.limits = limits;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AutoCloseable admission;
        try { admission = limits.admitSocket(); }
        catch (com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection limited) {
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason(limited.reason().name()));
            return;
        }
        String ip = (String) session.getAttributes().get(ClientConnection.CLIENT_IP);
        try {
            limits.checkNewSocket(ip);
            long deadline = limits.renewSocket(ip, null, session.getId());
            var connection = new ClientConnection(session, null,
                    properties.getSession().getOutboundQueueCapacity(), properties.getSession().getSendTimeout());
            connection.renewLimits(deadline);
            registry.add(connection);
            connection.onClosed(() -> {
                try { registry.remove(session.getId()); }
                finally {
                    try { limits.releaseSocket(ip, connection.authenticatedUserId(), session.getId()); }
                    finally { try { admission.close(); } catch (Exception failure) { throw new IllegalStateException(failure); } }
                }
            });
        } catch (RuntimeException failure) {
            limits.releaseSocket(ip, null, session.getId());
            admission.close();
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason(failure instanceof com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection
                    ? failure.getMessage() : "websocket admission unavailable"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientConnection connection = connection(session);
        String operation="";
        try {
            limits.checkSocketMessage(connection);
            var envelope=objectMapper.readTree(message.getPayload());
            if(!envelope.isObject()) throw new IllegalArgumentException("invalid websocket command");
            operation=envelope.path("op").asString("");
            if(operation.startsWith("c2c.")) {
                if (connection.apiKey() != null) throw new IllegalArgumentException("API key sessions do not support C2C commands");
                if(c2c==null) throw new IllegalArgumentException("C2C is not available");
                c2c.handle(connection,objectMapper.treeToValue(envelope,com.surprising.gateway.c2c.websocket.C2cChatRealtime.Command.class));
                connection.admittedMessage();
                return;
            }
            if ("authenticateApiKey".equalsIgnoreCase(operation)) {
                limits.checkSocketAuthentication(connection.clientIp());
                var proof = objectMapper.treeToValue(envelope, ApiKeyProof.class);
                if (proof.id() != null && proof.id().length() > 64) throw new IllegalArgumentException("websocket id is too long");
                var challenge = connection.apiChallenge();
                long user = authenticator.authenticateApiKey(proof.apiKey(), connection.clientIp(),
                        proof.timestamp(), proof.signature(), challenge.challenge());
                synchronized (connection) {
                    try {
                        connection.renewLimits(limits.renewSocket(connection.clientIp(), user, connection.id()));
                        connection.authenticateApiKey(user, proof.apiKey(), challenge);
                    } catch (RuntimeException failure) {
                        limits.releaseSocket(connection.clientIp(), user, connection.id());
                        connection.close(CloseStatus.SERVICE_OVERLOAD.withReason(failure instanceof com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection rejected
                                ? rejected.reason().name() : "websocket authorization unavailable"));
                        throw failure;
                    }
                }
                connection.send(objectMapper.writeValueAsString(WsServerMessage.authenticated(proof.id(), user)));
                connection.admittedMessage();
                return;
            }
            WsClientCommand command = objectMapper.treeToValue(envelope, WsClientCommand.class);
            String op = command.op() == null ? "" : command.op().trim().toLowerCase();
            log.debug("ws.command.start session={} op={}", session.getId(), op);
            switch (op) {
                case "apichallenge" -> connection.send(objectMapper.writeValueAsString(new WsServerMessage(
                        "apiChallenge", command.id(), null, null, null, null, null,
                        connection.issueApiChallenge(), null, java.time.Instant.now())));
                case "authenticate" -> authenticate(connection, command);
                case "subscribe" -> subscribe(connection, command);
                case "unsubscribe" -> unsubscribe(connection, command);
                case "ping" -> connection.send(objectMapper.writeValueAsString(WsServerMessage.pong(command.id())));
                default -> throw new IllegalArgumentException("unsupported websocket op: " + command.op());
            }
            connection.admittedMessage();
            log.debug("ws.command.end session={} op={} result=OK", session.getId(), op);
        } catch (com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection limited) {
            connection.send(objectMapper.writeValueAsString(new WsServerMessage("error", null, null, null, null, null, null,
                    java.util.Map.of("code", limited.reason().name(), "retryAfterSeconds", limited.retryAfterSeconds()),
                    limited.reason().name(), java.time.Instant.now())));
            if (limited.reason() != com.surprising.gateway.provider.service.GatewayRequestLimits.Reason.RATE_LIMITED || connection.rejectLimit())
                connection.close(CloseStatus.SERVICE_OVERLOAD.withReason(limited.reason().name()));
        } catch (Exception ex) {
            logFailure("ws.command.failed", session.getId(), ex);
            if(operation.startsWith("c2c.")) {
                String error=(ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) && ex.getMessage()!=null
                        ?ex.getMessage():"聊天请求未完成，请稍后重试";
                connection.send(objectMapper.writeValueAsString(java.util.Map.of("op","c2c.error","channel","c2c.messages","message",error)));
            } else connection.send(objectMapper.writeValueAsString(WsServerMessage.error(null, ex.getMessage())));
        }
    }

    private record ApiKeyProof(String op, String id, String apiKey, long timestamp, String signature) {}

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if(c2c!=null)c2c.closed(session.getId());
        registry.remove(session.getId());
    }

    private void subscribe(ClientConnection connection, WsClientCommand command) {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(command, connection.authenticatedUserId());
        if (topic.userId() != null) {
            long verified = connection.apiKey() == null ? authenticator.principal(connection.accessToken(), connection.clientIp()).userId()
                    : authenticator.apiKeyUser(connection.apiKey(), connection.clientIp());
            if (verified != topic.userId()) throw new IllegalArgumentException("websocket identity mismatch");
        }
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
        limits.checkSocketAuthentication(connection.clientIp());
        var principal = authenticator.principal(command.token(), connection.clientIp());
        long userId = principal.userId();
        synchronized (connection) {
            try {
                if (connection.authenticatedUserId() != null && connection.authenticatedUserId() != userId)
                    throw new IllegalArgumentException("websocket session is already authenticated");
                connection.renewLimits(limits.renewSocket(connection.clientIp(), userId, connection.id()));
                connection.authenticate(userId, command.token(), principal.expiresAt());
            } catch (RuntimeException failure) {
                limits.releaseSocket(connection.clientIp(), userId, connection.id());
                connection.close(CloseStatus.SERVICE_OVERLOAD.withReason(failure instanceof com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection rejected
                        ? rejected.reason().name() : "websocket authorization unavailable"));
                throw failure;
            }
        }
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
