package com.surprising.price.consumer.config;

import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Component("markPriceConsumerProperties")
@ConfigurationProperties(prefix = "surprising.price.consumer")
public class MarkPriceConsumerProperties {

    @Setter
    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    @Setter
    private Duration maxAge = Duration.ofSeconds(3);
    @Setter
    private Duration allowedFutureSkew = Duration.ofSeconds(1);
    private List<String> requiredInstrumentIds = List.of();

    public void setRequiredInstrumentIds(List<String> requiredInstrumentIds) {
        this.requiredInstrumentIds = requiredInstrumentIds == null ? List.of() : requiredInstrumentIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
