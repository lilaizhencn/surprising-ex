package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.config.TriggerProperties;

final class TriggerTopicGuard {

    private TriggerTopicGuard() {
    }

    static void requireCurrentProductTopic(TriggerProperties properties,
                                           String topic,
                                           String expectedTopic,
                                           String streamName) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return;
        }
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException(streamName + " topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }
}
