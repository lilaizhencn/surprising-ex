package com.surprising.aeron.service.bootstrap;

import com.surprising.aeron.service.SurprisingCoreBootstrap;
import com.surprising.aeron.service.cluster.ClusterTopology;
import org.agrona.concurrent.ShutdownSignalBarrier;

/**
 * Spring-managed infrastructure lifecycle for one Aeron core node.
 *
 * <p>This bean owns only the node bootstrap barrier. Trading state remains owned by the
 * Aeron service callback thread and is created/recovered by the existing core lifecycle.</p>
 */
public final class SurprisingCoreNode implements AutoCloseable {

    private final ClusterTopology topology;
    private volatile ShutdownSignalBarrier barrier;

    public SurprisingCoreNode(ClusterTopology topology) {
        this.topology = topology;
    }

    /** Runs the existing blocking Aeron node lifecycle on the application main thread. */
    public void run() {
        ShutdownSignalBarrier next = new ShutdownSignalBarrier();
        barrier = next;
        try {
            SurprisingCoreBootstrap.run(topology, next);
        } finally {
            barrier = null;
            next.close();
        }
    }

    /** Signals the blocking node lifecycle; resource closure remains in its existing try-with-resources scope. */
    @Override
    public void close() {
        ShutdownSignalBarrier current = barrier;
        if (current != null) current.signalAll();
    }
}
