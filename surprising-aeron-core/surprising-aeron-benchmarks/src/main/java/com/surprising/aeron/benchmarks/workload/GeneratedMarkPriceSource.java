package com.surprising.aeron.benchmarks.workload;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 固定报价的独立模拟行情源；源端生成/传播延迟不占用交易发压线程。 */
final class GeneratedMarkPriceSource implements AutoCloseable {
    /** 初始化后不再修改的各币对报价，由行情源持有。 */
    private final long[] prices;
    /** 源线程发布完整一期报价的真实生成时间，消费者只读。 */
    private volatile long publishedTimestamp;
    private volatile boolean running = true;
    private volatile Throwable failure;
    private final Thread worker;

    record Quote(long priceTicks, long generatedAtEpochMillis) {}

    GeneratedMarkPriceSource(long[] prices) {
        this.prices = prices.clone();
        if (prices.length == 0) throw new IllegalArgumentException("prices required");
        for (long price : this.prices) if (price <= 0) throw new IllegalArgumentException("positive price required");
        var ready = new CompletableFuture<Void>();
        worker = Thread.ofPlatform().daemon(true).name("benchmark-mark-price-source").start(() -> {
            try {
                while (running) {
                    // 报价在源端已确定；延迟后才发布原始时间，不倒填时间或放宽Core规则。
                    publishedTimestamp = GeneratedPriceClock.timestamp();
                    ready.complete(null);
                }
            } catch (Throwable error) {
                if (running) failure = error;
                ready.completeExceptionally(error);
            }
        });
        // 只在预热前启动行情源时等待首期报价，交易发压路径不等待。
        try { ready.get(5, TimeUnit.SECONDS); }
        catch (Exception error) {
            close();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("mark price source did not start", error);
        }
    }

    Quote quote(int symbol) {
        if (failure != null) throw new IllegalStateException("mark price source failed", failure);
        if (!running) throw new IllegalStateException("mark price source closed");
        return new Quote(prices[symbol], publishedTimestamp);
    }

    @Override public void close() {
        running = false;
        worker.interrupt();
        try { worker.join(5_000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) throw new IllegalStateException("mark price source did not stop");
    }
}
