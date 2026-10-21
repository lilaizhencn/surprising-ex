package com.surprising.websocket.provider.task;

import com.surprising.websocket.provider.service.OpenInterestPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时线程入口；状态、业务规则及续办顺序由OpenInterestPublisher持有。 */
@Component
public final class OpenInterestPublishTask {
    private final OpenInterestPublisher service;

    public OpenInterestPublishTask(OpenInterestPublisher service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        service.publish();
    }
}
