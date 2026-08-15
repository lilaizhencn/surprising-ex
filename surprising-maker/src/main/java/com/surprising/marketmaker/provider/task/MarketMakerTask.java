package com.surprising.marketmaker.provider.task;

import com.surprising.marketmaker.provider.service.MarketMakerService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 做市引擎定时入口，只负责调用做市服务。
 */
@Component
public class MarketMakerTask {

    private final MarketMakerService marketMakerService;

    public MarketMakerTask(MarketMakerService marketMakerService) {
        this.marketMakerService = marketMakerService;
    }

    @Scheduled(fixedDelayString = "${surprising.market-maker.engine.cycle-delay-ms:250}")
    public void runCycle() {
        marketMakerService.scheduledRun();
    }
}
