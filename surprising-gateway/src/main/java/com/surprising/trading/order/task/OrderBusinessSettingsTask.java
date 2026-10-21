package com.surprising.trading.order.task;

import com.surprising.trading.order.service.OrderBusinessSettingsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在各产品线自己的业务容器中刷新规则，沿用该容器的调度器。 */
@Component
public class OrderBusinessSettingsTask {
    private final OrderBusinessSettingsService settings;
    public OrderBusinessSettingsTask(OrderBusinessSettingsService settings) { this.settings = settings; }

    @Scheduled(fixedDelay = 1000)
    public void reload() { settings.reload(); }
}
