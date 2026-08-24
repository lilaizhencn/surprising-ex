package com.surprising.price;

import com.surprising.price.index.IndexPriceRuntimeHints;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.MarkPriceRuntimeHints;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.consumer.MarkPriceConsumerConfiguration;
import com.surprising.price.consumer.MarkPriceKafkaConsumer;
import com.surprising.price.consumer.MarkPriceConsumerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.surprising", excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {MarkPriceConsumerConfiguration.class, MarkPriceKafkaConsumer.class}))
@EnableFeignClients(basePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({IndexPriceProperties.class, MarkPriceProperties.class})
@ImportRuntimeHints({IndexPriceRuntimeHints.class, MarkPriceRuntimeHints.class})
public class SurprisingPriceApplication {

    private static final Logger log = LoggerFactory.getLogger(SurprisingPriceApplication.class);

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
        if (indexProperties.getKafka().getProductLine() != markProperties.getKafka().getProductLine()
                || consumerProperties.getProductLine() != markProperties.getKafka().getProductLine()) {
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

    public static void main(String[] args) {
        SpringApplication.run(SurprisingPriceApplication.class, args);
    }
}
