package com.surprising.realtime.provider.option.task;

import com.surprising.realtime.provider.option.OptionQuoteService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 定时线程入口；状态、业务规则及续办顺序由OptionQuoteService持有。 */
@Component
@ConditionalOnProperty(name = "surprising.candlestick.kafka.product-line", havingValue = "OPTION")
public final class OptionQuotePruneTask {
    private final OptionQuoteService service;

    public OptionQuotePruneTask(OptionQuoteService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 5000)
    public void prune() {
        service.prune();
    }
}
