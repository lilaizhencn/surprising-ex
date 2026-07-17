package com.surprising.price.consumer;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "surprising.price.consumer")
public class MarkPriceConsumerProperties {

    private String bootstrapServers = "localhost:9092";
    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    private boolean productTopicsEnabled;
    private String topic = "surprising.perp.mark.price.v1";
    private String groupId = "surprising-mark-price-cache-local";
    private Duration maxAge = Duration.ofSeconds(3);
    private Duration allowedFutureSkew = Duration.ofSeconds(1);
    private int concurrency = 1;
    private int maxPollRecords = 500;

    public String resolvedTopic() {
        return productTopicsEnabled ? ProductTopicNames.of(productLine).markPriceTopic() : topic;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public ProductLine getProductLine() {
        return productLine;
    }

    public void setProductLine(ProductLine productLine) {
        this.productLine = productLine;
    }

    public boolean isProductTopicsEnabled() {
        return productTopicsEnabled;
    }

    public void setProductTopicsEnabled(boolean productTopicsEnabled) {
        this.productTopicsEnabled = productTopicsEnabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Duration getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Duration maxAge) {
        this.maxAge = maxAge;
    }

    public Duration getAllowedFutureSkew() {
        return allowedFutureSkew;
    }

    public void setAllowedFutureSkew(Duration allowedFutureSkew) {
        this.allowedFutureSkew = allowedFutureSkew;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }
}
