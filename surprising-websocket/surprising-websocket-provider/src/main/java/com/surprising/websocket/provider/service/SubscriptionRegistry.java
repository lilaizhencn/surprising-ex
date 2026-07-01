package com.surprising.websocket.provider.service;

import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.api.model.WsServerMessage;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class SubscriptionRegistry {

    private final ObjectMapper objectMapper;
    private final WebSocketProperties properties;
    private final Map<String, ClientConnection> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<SubscriptionTopic>> sessionTopics = new ConcurrentHashMap<>();
    private final Map<SubscriptionTopic, Set<ClientConnection>> subscribers = new ConcurrentHashMap<>();

    public SubscriptionRegistry(ObjectMapper objectMapper, WebSocketProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void add(ClientConnection connection) {
        sessions.put(connection.id(), connection);
        sessionTopics.put(connection.id(), ConcurrentHashMap.newKeySet());
    }

    public ClientConnection connection(String sessionId) {
        ClientConnection connection = sessions.get(sessionId);
        if (connection == null) {
            throw new IllegalStateException("websocket session is not registered: " + sessionId);
        }
        return connection;
    }

    public void remove(String sessionId) {
        ClientConnection connection = sessions.remove(sessionId);
        Set<SubscriptionTopic> topics = sessionTopics.remove(sessionId);
        if (topics != null && connection != null) {
            for (SubscriptionTopic topic : topics) {
                Set<ClientConnection> connections = subscribers.get(topic);
                if (connections != null) {
                    connections.remove(connection);
                    if (connections.isEmpty()) {
                        subscribers.remove(topic, connections);
                    }
                }
            }
        }
        if (connection != null) {
            connection.close();
        }
    }

    public void subscribe(ClientConnection connection, SubscriptionTopic topic) {
        Set<SubscriptionTopic> topics = sessionTopics.computeIfAbsent(connection.id(), key -> ConcurrentHashMap.newKeySet());
        if (topics.size() >= properties.getSession().getMaxSubscriptions() && !topics.contains(topic)) {
            throw new IllegalStateException("maximum websocket subscriptions exceeded");
        }
        topics.add(topic);
        subscribers.computeIfAbsent(topic, key -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    public void unsubscribe(ClientConnection connection, SubscriptionTopic topic) {
        Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
        if (topics != null) {
            topics.remove(topic);
        }
        Set<ClientConnection> connections = subscribers.get(topic);
        if (connections != null) {
            connections.remove(connection);
            if (connections.isEmpty()) {
                subscribers.remove(topic, connections);
            }
        }
    }

    public void publish(SubscriptionTopic topic, Object payload, Instant eventTime) {
        send(topic, payload, eventTime);
        if (!topic.channel().isPublicChannel() && !SubscriptionTopic.WILDCARD.equals(topic.symbol())) {
            send(new SubscriptionTopic(topic.channel(), SubscriptionTopic.WILDCARD, topic.period(), topic.userId()),
                    payload, eventTime);
        }
    }

    public int subscriberCount(SubscriptionTopic topic) {
        return subscribers.getOrDefault(topic, Set.of()).size();
    }

    private void send(SubscriptionTopic topic, Object payload, Instant eventTime) {
        Set<ClientConnection> connections = subscribers.get(topic);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        String message = objectMapper.writeValueAsString(WsServerMessage.event(topic, payload, eventTime));
        for (ClientConnection connection : connections) {
            if (!connection.send(message)) {
                remove(connection.id());
            }
        }
    }

    public SubscriptionTopic publicTopic(WsChannel channel, String symbol) {
        return new SubscriptionTopic(channel, symbol, null, null);
    }
}
