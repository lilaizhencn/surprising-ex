package com.surprising.instrument.provider.task;

import com.surprising.instrument.provider.service.InstrumentLifecycleService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 交易品种生命周期定时入口，只负责调用生命周期服务。
 */
@Component
public class InstrumentLifecycleTask {

    private final InstrumentLifecycleService lifecycleService;

    public InstrumentLifecycleTask(InstrumentLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${surprising.instrument.lifecycle.poll-delay-ms:1000}")
    public void advanceLifecycle() {
        lifecycleService.advanceLifecycle();
    }
}
