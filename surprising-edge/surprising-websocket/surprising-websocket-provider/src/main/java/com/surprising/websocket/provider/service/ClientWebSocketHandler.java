package com.surprising.websocket.provider.service;

import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsClientCommand;
import com.surprising.websocket.api.model.WsServerMessage;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
public class ClientWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final SubscriptionRegistry registry;
    private final WebSocketProperties properties;
    private final WebSocketJwtAuthenticator jwtAuthenticator;

    public ClientWebSocketHandler(ObjectMapper objectMapper,
                                  SubscriptionRegistry registry,
                                  WebSocketProperties properties,
                                  WebSocketJwtAuthenticator jwtAuthenticator) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.properties = properties;
        this.jwtAuthenticator = jwtAuthenticator;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = authenticatedUserId(session);
        registry.add(new ClientConnection(session, userId,
                properties.getSession().getOutboundQueueCapacity(),
                properties.getSession().getSendTimeout()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientConnection connection = connection(session);
        try {
            WsClientCommand command = objectMapper.readValue(message.getPayload(), WsClientCommand.class);
            String op = command.op() == null ? "" : command.op().trim().toLowerCase();
            switch (op) {
                case "subscribe" -> subscribe(connection, command);
                case "unsubscribe" -> unsubscribe(connection, command);
                case "ping" -> connection.send(objectMapper.writeValueAsString(WsServerMessage.pong(command.id())));
                default -> throw new IllegalArgumentException("unsupported websocket op: " + command.op());
            }
        } catch (Exception ex) {
            connection.send(objectMapper.writeValueAsString(WsServerMessage.error(null, ex.getMessage())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.remove(session.getId());
    }

    private void subscribe(ClientConnection connection, WsClientCommand command) {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(command, connection.authenticatedUserId());
        registry.subscribe(connection, topic);
        connection.send(objectMapper.writeValueAsString(WsServerMessage.ack(command.id(), topic)));
    }

    private void unsubscribe(ClientConnection connection, WsClientCommand command) {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(command, connection.authenticatedUserId());
        registry.unsubscribe(connection, topic);
        connection.send(objectMapper.writeValueAsString(WsServerMessage.unack(command.id(), topic)));
    }

    private ClientConnection connection(WebSocketSession session) {
        return registry.connection(session.getId());
    }

    private Long authenticatedUserId(WebSocketSession session) {
        URI uri = session.getUri();
        String queryToken = queryValue(uri, "token");
        if (queryToken != null && !queryToken.isBlank()) {
            return jwtAuthenticator.authenticate(queryToken.trim());
        }
        List<String> headers = session.getHandshakeHeaders().get(properties.getSecurity().getUserIdHeader());
        if (headers != null && !headers.isEmpty() && !headers.get(0).isBlank()) {
            return Long.parseLong(headers.get(0).trim());
        }
        HttpHeaders handshakeHeaders = session.getHandshakeHeaders();
        List<String> forwarded = handshakeHeaders.get("X-Forwarded-User-Id");
        if (forwarded != null && !forwarded.isEmpty() && !forwarded.get(0).isBlank()) {
            return Long.parseLong(forwarded.get(0).trim());
        }
        String queryUserId = queryValue(uri, "userId");
        if (properties.getSecurity().isAllowQueryUserIdFallback()
                && queryUserId != null && !queryUserId.isBlank()) {
            return Long.parseLong(queryUserId.trim());
        }
        return null;
    }

    private String queryValue(URI uri, String name) {
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String part : uri.getQuery().split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }
}
