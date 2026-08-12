package com.surprising.liquidation.provider.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class LiquidationWorkerConfiguration {

    @Bean("liquidationCandidateTaskExecutor")
    public TaskExecutor liquidationCandidateTaskExecutor(LiquidationProperties properties) {
        int workers = properties.getRedisIndex().getWorkerCount();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(workers);
        executor.setPrestartAllCoreThreads(true);
        executor.setThreadNamePrefix("liquidation-candidate-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
