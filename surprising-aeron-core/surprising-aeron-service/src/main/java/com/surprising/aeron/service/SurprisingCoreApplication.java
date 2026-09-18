package com.surprising.aeron.service;

import com.surprising.aeron.service.config.AeronCoreLifecycle;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

/** Spring Boot composition root for the Aeron node; it is intentionally non-web. */
@SpringBootConfiguration
@ComponentScan
public class SurprisingCoreApplication {

    private SurprisingCoreApplication() {
    }

    static void main(String[] args) {
        SpringApplication application = new SpringApplication(SurprisingCoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(args)) {
            context.getBean(AeronCoreLifecycle.class).startAndAwaitShutdown();
        }
    }
}
