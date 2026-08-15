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
    }

    public static void main(String[] args) {
        SpringApplication.run(SurprisingPriceApplication.class, args);
    }
}
