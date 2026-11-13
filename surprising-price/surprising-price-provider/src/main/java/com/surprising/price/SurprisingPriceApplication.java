package com.surprising.price;

import lombok.extern.slf4j.Slf4j;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.consumer.config.MarkPriceConsumerProperties;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

@Configuration("priceProviderConfiguration")
@Slf4j
public class SurprisingPriceApplication {


    private final IndexPriceProperties indexProperties;
    private final MarkPriceProperties markProperties;
    private final MarkPriceConsumerProperties consumerProperties;

    public SurprisingPriceApplication(IndexPriceProperties indexProperties,
                                       MarkPriceProperties markProperties,
                                       MarkPriceConsumerProperties consumerProperties) {
        this.indexProperties = indexProperties;
        this.markProperties = markProperties;
        this.consumerProperties = consumerProperties;
    }

    @PostConstruct
    void validateProductLineAlignment() {
        if (indexProperties.getProductLine() != markProperties.getProductLine()
                || consumerProperties.getProductLine() != markProperties.getProductLine()) {
            throw new IllegalStateException("index and mark price product-line configuration must match");
        }
        log.info("Effective price matrix configuration markPublishIntervalMs={} indexPollDelayMs={} "
                        + "indexMinValidSources={} indexMaxSourceAge={} indexWebSocketEnabled={} "
                        + "indexRestFallbackEnabled={} indexWebSocketIdleTimeout={} "
                        + "indexWebSocketReconnectInitialDelay={} indexWebSocketReconnectMaxDelay={} "
                        + "indexWebSocketHealthCheckInterval={}",
                markProperties.getCalculation().getPublishIntervalMs(),
                indexProperties.getCalculation().getPollDelayMs(),
                indexProperties.getCalculation().getMinValidSources(),
                indexProperties.getCalculation().getMaxSourceAge(),
                indexProperties.getWebSocket().isEnabled(),
                indexProperties.getWebSocket().isRestFallbackEnabled(),
                indexProperties.getWebSocket().getIdleTimeout(),
                indexProperties.getWebSocket().getReconnectInitialDelay(),
                indexProperties.getWebSocket().getReconnectMaxDelay(),
                indexProperties.getWebSocket().getHealthCheckInterval());
    }
}
