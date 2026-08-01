package com.surprising.product.api;

public record ProductTopicNames(ProductLine productLine, String namespace) {

    /** Instrument 是全局事实源，所有产品线共享同一个配置事件 Topic。 */
    public static final String INSTRUMENT_EVENTS_TOPIC = "surprising.instrument.events.v1";

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

    /** 全局 Instrument 配置事件 Topic；产品线由事件载荷和本地快照隔离。 */
    public String instrumentEventsTopic() {
        // Instrument 配置是全局事实源，所有产品线共用一个事件 Topic，靠 key 和 payload 隔离产品线。
        return INSTRUMENT_EVENTS_TOPIC;
    }

    /** 产品线隔离的费率配置事件 Topic。 */
    public String feeScheduleEventsTopic() {
        return topic("fee.schedule.events");
    }

    public String accountPositionEventsTopic() {
        return topic("account.position.events");
    }

    /** 产品线隔离的未平仓量分片快照事件 Topic。 */
    public String accountOpenInterestEventsTopic() {
        return topic("account.open-interest.events");
    }

    public String accountLiquidationFeeEventsTopic() {
        return topic("account.liquidation-fee.events");
    }

    /** 账户单写者发布的风险钱包完整快照事件。 */
    public String accountRiskWalletEventsTopic() {
        return topic("account.risk-wallet.events");
    }

    /** 永续账户单写者发布的完整用户状态快照事件。 */
    public String accountStateEventsTopic() {
        return topic("account.state.events");
    }

    public String accountUserCommandsTopic() {
        return topic("account.user.commands");
    }

    public String accountUserCommandsDltTopic() {
        return topic("account.user.commands.dlt");
    }

    public String accountCommandResultsTopic() {
        return topic("account.command.results");
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
