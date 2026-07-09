package com.surprising.websocket.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

public record WsServerMessage(
        String op,
        String id,
        String channel,
        String symbol,
        String period,
        Long userId,
        ProductLine productLine,
        Object data,
        String error,
        Instant eventTime) {

    public static WsServerMessage ack(String id, SubscriptionTopic topic) {
        return new WsServerMessage("subscribed", id, topic.channel().code(), topic.symbol(), topic.period(),
                topic.userId(), topic.productLine(), null, null, Instant.now());
    }

    public static WsServerMessage unack(String id, SubscriptionTopic topic) {
        return new WsServerMessage("unsubscribed", id, topic.channel().code(), topic.symbol(), topic.period(),
                topic.userId(), topic.productLine(), null, null, Instant.now());
    }

    public static WsServerMessage event(SubscriptionTopic topic, Object data, Instant eventTime) {
        return new WsServerMessage("event", null, topic.channel().code(), topic.symbol(), topic.period(),
                topic.userId(), topic.productLine(), data, null, eventTime);
    }

    public static WsServerMessage pong(String id) {
        return new WsServerMessage("pong", id, null, null, null, null, null, null, null, Instant.now());
    }

    public static WsServerMessage error(String id, String error) {
        return new WsServerMessage("error", id, null, null, null, null, null, null, error, Instant.now());
    }
}
