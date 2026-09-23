package com.surprising.price.consumer;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Component("markPriceConsumerProperties")
@ConfigurationProperties(prefix = "surprising.price.consumer")
public class MarkPriceConsumerProperties {

    @Setter
    private String bootstrapServers = "localhost:9092";
    @Setter
    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    @Setter
    private String groupId = "surprising-mark-price-cache-local";
    @Setter
    private Duration maxAge = Duration.ofSeconds(3);
    @Setter
    private Duration allowedFutureSkew = Duration.ofSeconds(1);
    @Setter
    private int concurrency = 1;
    @Setter
    private int maxPollRecords = 500;
    private List<String> requiredSymbols = List.of();

    public String resolvedTopic() {
        return ProductTopicNames.of(productLine).priceEventsTopic();
    }

    public void setRequiredSymbols(List<String> requiredSymbols) {
        this.requiredSymbols = requiredSymbols == null ? List.of() : requiredSymbols.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
