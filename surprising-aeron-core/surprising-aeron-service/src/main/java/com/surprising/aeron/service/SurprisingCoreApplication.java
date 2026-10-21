package com.surprising.aeron.service;

import com.surprising.aeron.service.bootstrap.AeronCoreLifecycle;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

/**
 * 一个交易 Core 节点的启动入口，负责把 Spring 配置和 Aeron 资源装配起来。
 * 这个进程只运行交易集群，不启动 HTTP 服务；启动完成后会一直等到收到关闭信号。
 */
@SpringBootConfiguration
@ComponentScan
public class SurprisingCoreApplication {

    /** 入口配置交给 Spring 创建，业务代码不需要手动创建这个类。 */
    private SurprisingCoreApplication() {
    }

    /**
     * 先加载配置和组件，再启动集群；节点退出时一并关闭 Spring 上下文。
     *
     * @param args 启动参数，例如产品线、节点编号和数据目录
     */
    static void main(String[] args) {
        // 保存本次启动器，明确告诉 Spring 这是一个不提供网页接口的进程。
        SpringApplication application = new SpringApplication(SurprisingCoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (/* 持有已加载的配置和组件，离开这里时自动触发资源关闭。 */
                ConfigurableApplicationContext context = application.run(args)) {
            context.getBean(AeronCoreLifecycle.class).startAndAwaitShutdown();
        }
    }
}
