package com.surprising.edge.provider;

import com.surprising.gateway.provider.SurprisingGatewayApplication;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.websocket.provider.SurprisingWebSocketApplication;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.surprising.gateway.provider",
                "com.surprising.websocket.provider"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        SurprisingGatewayApplication.class,
                        SurprisingWebSocketApplication.class
                }
        )
)
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({
        GatewayProperties.class,
        WebSocketProperties.class
})
public class SurprisingEdgeApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SurprisingEdgeApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", "surprising-edge-provider"));
        application.run(args);
    }

    @Bean
    ApplicationRunner edgeLocalWebSocketRoutes(GatewayProperties properties,
                                               @Value("${surprising.edge.local-base-url:http://localhost:9094}")
                                               String localBaseUrl) {
        return args -> {
            GatewayProperties.BackendRoute publicRoute = properties.getRoutes().get("websocket");
            if (publicRoute != null) {
                publicRoute.setBaseUrl(localBaseUrl);
            }
            GatewayProperties.BackendRoute adminRoute = properties.getAdminRoutes().get("websocket-admin");
            if (adminRoute != null) {
                adminRoute.setBaseUrl(localBaseUrl);
            }
        };
    }
}
