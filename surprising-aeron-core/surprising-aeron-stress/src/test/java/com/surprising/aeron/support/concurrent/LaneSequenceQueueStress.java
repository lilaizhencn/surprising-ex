package com.surprising.aeron.support.concurrent;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.Z_Result;

/**
 * One lane publishes Core sequences and the owner consumes them.
 * The volatile cursor must not become visible before the slot write.
 */
@JCStressTest
@Description("SPSC lane sequence queue publishes each positive sequence once and in order")
@Outcome(id = "true", expect = Expect.ACCEPTABLE, desc = "Every sequence arrived once, in order, and the queue drained")
@Outcome(id = "false", expect = Expect.FORBIDDEN, desc = "Lost, duplicated, reordered, or torn sequence")
@State
public class LaneSequenceQueueStress {
    private static final int COUNT = 64;
    private final LaneSequenceQueue queue = new LaneSequenceQueue(128);
    private boolean broken;

    @Actor
    public void publisher() {
        for (int sequence = 1; sequence <= COUNT; sequence++) queue.publish(sequence);
    }

    @Actor
    public void consumer() {
        int expected = 1;
        while (expected <= COUNT) {
            long sequence = queue.poll();
            if (sequence == 0L) {
                Thread.onSpinWait();
                continue;
            }
            if (sequence != expected) {
                broken = true;
                return;
            }
            expected++;
        }
    }

    @Arbiter
    public void arbiter(Z_Result result) {
        result.r1 = !broken && queue.poll() == 0L;
    }
}
