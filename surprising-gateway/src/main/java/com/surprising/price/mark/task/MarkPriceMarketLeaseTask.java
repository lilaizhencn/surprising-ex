package com.surprising.price.mark.task;

import com.surprising.price.mark.service.MarkPriceMarketSubscription;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 定时线程入口；状态、业务规则及续办顺序由MarkPriceMarketSubscription持有。 */
@Component
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true")
public final class MarkPriceMarketLeaseTask {
    private final MarkPriceMarketSubscription service;

    public MarkPriceMarketLeaseTask(MarkPriceMarketSubscription service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 3000)
    public void renewSubscriptions() {
        service.renewSubscriptions();
    }
}
