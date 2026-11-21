package com.surprising.aeron.benchmarks.latency;

import com.surprising.aeron.support.concurrent.LaneSequenceQueue;
import net.openhft.chronicle.jlbh.JLBH;
import net.openhft.chronicle.jlbh.JLBHOptions;
import net.openhft.chronicle.jlbh.JLBHTask;

/**
 * Open-loop latency of one SPSC publish plus the matching poll.
 * JLBH accounts for coordinated omission and prints an HdrHistogram.
 */
public final class LaneSequenceOpenLoopBenchmark implements JLBHTask {
    private JLBH jlbh;
    private LaneSequenceQueue queue;
    private int sequence;

    public static void main(String[] args) {
        var options = new JLBHOptions()
                .warmUpIterations(1)
                .iterations(2)
                .throughput(200_000)
                .runs(2)
                .accountForCoordinatedOmission(true)
                .jlbhTask(new LaneSequenceOpenLoopBenchmark());
        new JLBH(options).start();
    }

    @Override
    public void init(JLBH jlbh) {
        this.jlbh = jlbh;
        queue = new LaneSequenceQueue(1024);
    }

    @Override
    public void run(long startTimeNS) {
        int next = sequence == Integer.MAX_VALUE ? 1 : sequence + 1;
        sequence = next;
        queue.publish(next);
        long observed = queue.poll();
        if (observed != next) throw new IllegalStateException("sequence gap observed=" + observed + " expected=" + next);
        jlbh.sample(System.nanoTime() - startTimeNS);
    }
}
