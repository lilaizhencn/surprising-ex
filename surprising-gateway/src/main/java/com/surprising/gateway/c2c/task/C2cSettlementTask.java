package com.surprising.gateway.c2c.task;

import com.surprising.gateway.c2c.service.C2cSettlement;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 定时线程入口；状态、业务规则及续办顺序由C2cSettlement持有。 */
@Component
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public final class C2cSettlementTask {
    private final C2cSettlement service;

    public C2cSettlementTask(C2cSettlement service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString="${surprising.c2c.settlement-delay-ms:1000}",scheduler="c2cSettlementScheduler")
    public void settle() {
        service.settle();
    }
}
