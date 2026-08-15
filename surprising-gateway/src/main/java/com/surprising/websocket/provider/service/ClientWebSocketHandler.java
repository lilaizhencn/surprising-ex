package com.surprising.websocket.provider.service;

import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsClientCommand;
import com.surprising.websocket.api.model.WsServerMessage;
import com.surprising.websocket.provider.config.WebSocketProperties;
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
                case "authenticate" -> authenticate(connection, command);
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

    private void authenticate(ClientConnection connection, WsClientCommand command) {
        long userId = jwtAuthenticator.authenticate(command.token());
        connection.authenticate(userId);
        connection.send(objectMapper.writeValueAsString(WsServerMessage.authenticated(command.id(), userId)));
    }

    private void unsubscribe(ClientConnection connection, WsClientCommand command) {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(command, connection.authenticatedUserId());
        registry.unsubscribe(connection, topic);
        connection.send(objectMapper.writeValueAsString(WsServerMessage.unack(command.id(), topic)));
    }

    private ClientConnection connection(WebSocketSession session) {
        return registry.connection(session.getId());
    }

    Long authenticatedUserId(WebSocketSession session) {
        HttpHeaders headers = session.getHandshakeHeaders();
        String headerValue = headers.getFirst(properties.getSecurity().getUserIdHeader());
        if (headerValue != null && !headerValue.isBlank()) {
            return requirePositiveUserId(headerValue);
        }
        return null;
    }

    private static long requirePositiveUserId(String value) {
        try {
            long userId = Long.parseLong(value.trim());
            if (userId <= 0L) {
                throw new IllegalArgumentException("websocket userId must be positive");
            }
            return userId;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("websocket userId must be a positive integer", ex);
        }
    }
}
