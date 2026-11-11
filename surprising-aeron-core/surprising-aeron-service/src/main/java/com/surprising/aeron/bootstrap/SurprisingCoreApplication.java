package com.surprising.aeron.bootstrap;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

/** 一个交易 Core 节点的启动入口，负责把 Spring 配置和 Aeron 资源装配起来。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@SpringBootConfiguration(proxyBeanMethods = false)
@ComponentScan("com.surprising.aeron")
public class SurprisingCoreApplication {

    /** 先加载配置和组件，再启动集群。 */
    static void main(String[] args) {
        SpringApplication application = new SpringApplication(SurprisingCoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (
                ConfigurableApplicationContext context = application.run(args)) {
            context.getBean(AeronCoreLifecycle.class).startAndAwaitShutdown();
        }
    }
}
