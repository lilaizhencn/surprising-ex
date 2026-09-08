package com.surprising.aeron.service.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import io.aeron.exceptions.AeronException;
import io.aeron.exceptions.DriverTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.AgentTerminationException;
import org.junit.jupiter.api.Test;

class ClusterFatalErrorTest {
    @Test
    void fatalConductorFailureStopsTheProcessBeforeMappedResourceCleanup() {
        AtomicInteger exit = new AtomicInteger();
        var handler = SurprisingClusterNode.errorHandler("test", exit::set);
        handler.onError(new DriverTimeoutException("paused beyond driver liveness"));
        assertThat(exit).hasValue(1);
        exit.set(0);
        handler.onError(new AgentTerminationException("lane failed"));
        assertThat(exit).hasValue(1);
    }

    @Test
    void recoverableElectionWarningDoesNotStopTheProcess() {
        AtomicInteger exit = new AtomicInteger();
        SurprisingClusterNode.errorHandler("test", exit::set)
                .onError(new AeronException("leader heartbeat timeout", AeronException.Category.WARN));
        assertThat(exit).hasValue(0);
    }
}
