package com.surprising.websocket.provider.model;

public record ChannelMetric(
        String channel,
        int topicCount,
        int subscriberCount) {
}
