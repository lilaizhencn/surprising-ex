package com.surprising.trading.order.task;

import com.surprising.trading.order.service.InstrumentCoreSyncService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时线程入口；状态、业务规则及续办顺序由InstrumentCoreSyncService持有。 */
@Component
public final class InstrumentCoreSyncTask {
    private final InstrumentCoreSyncService service;

    public InstrumentCoreSyncTask(InstrumentCoreSyncService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString="${trading.order.instrument-sync-delay-ms:250}")
    public void reconcile() {
        service.reconcile();
    }
}
