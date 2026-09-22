package com.surprising.instrument.provider.task;

import com.surprising.instrument.provider.service.InstrumentOutboxPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Instrument Outbox 定时入口，只负责调用发布服务。 */
@Component
public class InstrumentOutboxTask {

    private final InstrumentOutboxPublisher outboxPublisher;

    public InstrumentOutboxTask(InstrumentOutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    @Scheduled(fixedDelayString = "${surprising.instrument.outbox.publish-delay-ms:100}")
    public void publishPending() {
        outboxPublisher.publishPending();
    }

    @Scheduled(fixedDelayString = "${surprising.instrument.outbox.cleanup-delay-ms:60000}")
    public void cleanupPublished() {
        outboxPublisher.cleanupPublished();
    }
}
