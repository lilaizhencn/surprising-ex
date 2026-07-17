package com.surprising.product.api;

public record ProductTopicNames(ProductLine productLine, String namespace) {

    public ProductTopicNames {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
    }

    public static ProductTopicNames of(ProductLine productLine) {
        return new ProductTopicNames(productLine, "surprising." + productLine.topicSegment());
    }

    public String orderCommandsTopic() {
        return topic("order.commands");
    }

    public String orderEventsTopic() {
        return topic("order.events");
    }

    public String triggerOrderEventsTopic() {
        return topic("trigger-order.events");
    }

    public String matchResultsTopic() {
        return topic("match.results");
    }

    public String matchTradesTopic() {
        return topic("match.trades");
    }

    public String orderBookDepthTopic() {
        return topic("orderbook.depth");
    }

    public String indexPriceTopic() {
        return topic("index.price");
    }

    public String indexComponentsTopic() {
        return topic("index.components");
    }

    public String bookTickerTopic() {
        return topic("book.ticker");
    }

    public String markPriceTopic() {
        return topic("mark.price");
    }

    public String fundingRateTopic() {
        return topic("funding.rate");
    }

    public String publicTradesTopic() {
        return topic("trade.events");
    }

    public String candleEventsTopic() {
        return topic("candle.events");
    }

    public String accountPositionEventsTopic() {
        return topic("account.position.events");
    }

    public String accountLiquidationFeeEventsTopic() {
        return topic("account.liquidation-fee.events");
    }

    public String accountRiskEventsTopic() {
        return topic("risk.account.events");
    }

    public String positionRiskEventsTopic() {
        return topic("risk.position.events");
    }

    public String liquidationCandidatesTopic() {
        return topic("liquidation.candidates");
    }

    public String deliverySettlementsTopic() {
        return topic("delivery.settlements");
    }

    public String optionExercisesTopic() {
        return topic("option.exercises");
    }

    public String consumerGroup(String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service is required");
        }
        return "surprising-" + productLine.topicSegment() + "-" + service.trim() + "-v1";
    }

    private String topic(String eventName) {
        return namespace + "." + eventName + ".v1";
    }
}
