package com.surprising.price.provider;

import com.surprising.price.index.SurprisingIndexPriceApplication;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.SurprisingMarkPriceApplication;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.surprising.price.index",
                "com.surprising.price.mark"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        SurprisingIndexPriceApplication.class,
                        SurprisingMarkPriceApplication.class
                }
        )
)
@EnableFeignClients(basePackages = "com.surprising")
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({
        IndexPriceProperties.class,
        MarkPriceProperties.class
})
public class SurprisingPriceApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SurprisingPriceApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", "surprising-price-provider"));
        application.run(args);
    }
}
