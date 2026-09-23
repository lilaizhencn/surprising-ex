package com.surprising.funding.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "surprising.funding")
public class FundingProperties {

    @Valid
    private Kafka kafka = new Kafka();
    @Valid
    private Calculation calculation = new Calculation();
    @Valid
    private Settlement settlement = new Settlement();
    @Valid
    private Coordination coordination = new Coordination();

    @Getter
    public static class Kafka {
        @Setter
        private String bootstrapServers = "localhost:9092";
        private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
        @Setter
        private String cacheGroupId = "surprising-funding-rate-cache-local";
        @Setter
        private int concurrency = 1;
        @Setter
        private int maxPollRecords = 500;

        public void setProductLine(ProductLine productLine) {
            this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        }

        public String getFundingRateTopic() {
            return ProductTopicNames.of(productLine).fundingRateTopic();
        }

        public boolean isFundingProductLine() {
            return productLine.isFundingProduct();
        }


    }

    @Getter
    @Setter
    public static class Calculation {
        private boolean enabled = true;
        private long publishDelayMs = 1000L;
        private Duration maxMarkAge = Duration.ofSeconds(10);
        private Duration maxRateAge = Duration.ofSeconds(5);

    }

    @Getter
    public static class Settlement {
        @Setter
        private boolean enabled = true;
        @Setter
        @Min(1)
        private long settleDelayMs = 1000L;
        @Setter
        @Min(1)
        @Max(10_000)
        private int batchSize = 20;
        @Min(1)
        @Max(1_000)
        private int maxPagesPerRun = 8;

        public void setMaxPagesPerRun(int maxPagesPerRun) {
            if (maxPagesPerRun < 1 || maxPagesPerRun > 1_000) {
                throw new IllegalArgumentException("maxPagesPerRun must be in [1,1000]");
            }
            this.maxPagesPerRun = maxPagesPerRun;
        }

    }

    @Getter
    @Setter
    public static class Coordination {
        private boolean enabled = true;
        private String nodeId;
        private Duration leaseDuration = Duration.ofSeconds(15);

    }
}
