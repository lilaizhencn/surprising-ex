package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.HdrHistogram.Histogram;

/** Concurrent price, risk/lifecycle and read-model traffic around the continuously loaded trader. */
final class ClusterOperationalSideLoad implements AutoCloseable {
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ConcurrentMap<String,Histogram> latency = new ConcurrentHashMap<>();
    private final AtomicLong fills=new AtomicLong(),lastTerminal=new AtomicLong();
    private final AtomicLong extraBatchItems = new AtomicLong();
    private final OperationalEndpoint prices;
    private final OperationalLifecycle lifecycle;
    private final OperationalUserQueries queries;
    private final long[] marks, sequences;
    private final List<Thread> threads = new ArrayList<>();
    private final CompletableFuture<Void> initialPrices = new CompletableFuture<>();
    private volatile boolean running = true, priceRunning = true;
    private volatile long measurementStart = Long.MAX_VALUE, measurementEnd = Long.MAX_VALUE;

    ClusterOperationalSideLoad(long seed,List<Long> users,long[] marks,long[] sequences) {
        this.marks=marks.clone(); this.sequences=sequences.clone();
        OperationalEndpoint createdPrices=null;
        OperationalLifecycle createdLifecycle=null;
        try {
            createdPrices=new OperationalEndpoint(seed+1_000_000,"operational-prices",16,this);
            createdLifecycle=new OperationalLifecycle(seed+2_000_000,this);
            queries=new OperationalUserQueries(users,this);
        } catch(RuntimeException | Error error) {
            if(createdLifecycle!=null)createdLifecycle.close();
            if(createdPrices!=null)createdPrices.close();
            throw error;
        }
        prices=createdPrices;lifecycle=createdLifecycle;
    }

    void start() {
        startThread("operational-prices",() -> {
            try {feedPrices();} catch(Throwable error) {
                initialPrices.completeExceptionally(error);
                throw error;
            }
        });
        // Setup can exceed the freshness bound. Establish every price before trading starts;
        // subsequent refreshes run independently without draining the trading producer.
        initialPrices.join();
        lifecycle.setup();
        startThread("operational-risk",() -> { while(running) lifecycle.cycle(); });
        startThread("operational-queries",() -> { while(running) queries.step(); });
    }

    private void startThread(String name,Runnable work) {
        Thread t=Thread.ofPlatform().name(name).unstarted(() -> {
            try {work.run();} catch(Throwable error) {failure.compareAndSet(null,error);}
        });
        threads.add(t);t.start();
    }

    private void feedPrices() {
        var pending=new ArrayDeque<CompletableFuture<CoreResponse>>();
        while(priceRunning) {
            long next=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);
            for(int i=0;i<marks.length;i++) {
                // Wait only for this bounded price publisher; the trader continues independently.
                if(pending.size()==16) pending.removeFirst().join();
                pending.addLast(prices.send(CoreMessageType.APPLY_MARK_PRICE,0,
                        TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                                "JMH-MIX-"+i+"-USDT",1,marks[i],++sequences[i],GeneratedPriceClock.timestamp()))));
            }
            while(!pending.isEmpty())pending.removeFirst().join();
            initialPrices.complete(null);
            if(System.nanoTime()<next)LockSupport.parkNanos(next-System.nanoTime());
        }
    }

    void record(String kind,long start,long end) {
        if(start<measurementStart || start>=measurementEnd)return;
        Histogram h=latency.computeIfAbsent(kind,k->new Histogram(TimeUnit.MINUTES.toNanos(1),3));
        synchronized(h){h.recordValue(Math.max(1,end-start));}
        lastTerminal.accumulateAndGet(end,Math::max);
    }
    void recordFills(long start,long count) {
        if(start>=measurementStart && start<measurementEnd)fills.addAndGet(count);
    }
    void recordBatchItems(long start, int items) {
        if (start>=measurementStart && start<measurementEnd) extraBatchItems.addAndGet(Math.max(0,items-1));
    }
    void beginMeasurement(long now){measurementStart=now;}
    void endMeasurement(long now){measurementEnd=now;}
    void assertHealthy(){Throwable t=failure.get();if(t!=null)throw new IllegalStateException("operational actor failed",t);}
    long netDeposits(){return lifecycle.netDeposits();}
    void restoreAudit(long cycles,long deposits){lifecycle.restoreAudit(cycles,deposits);}
    long verifyAndBalance(){assertHealthy();return lifecycle.verifyAndBalance();}
    void stop() {
        running=false;
        // Finish the current financial lifecycle before stopping its price source.
        try {
            for(Thread t:threads)if(!t.getName().equals("operational-prices"))join(t);
        } finally {
            priceRunning=false;
            for(Thread t:threads)if(t.getName().equals("operational-prices"))join(t);
        }
        assertHealthy();
    }
    private static void join(Thread t) {
        try {t.join(60000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        if(t.isAlive())throw new IllegalStateException("actor failed to finish: "+t.getName());
    }
    void print() {
        for(String key:new TreeSet<>(latency.keySet())) {
            Histogram h=latency.get(key);
            System.out.printf(Locale.ROOT,"operational=%s requests=%d p50us=%d p90us=%d p95us=%d p99us=%d p999us=%d maxus=%d%n",
                    key,h.getTotalCount(),h.getValueAtPercentile(50)/1000,h.getValueAtPercentile(90)/1000,
                    h.getValueAtPercentile(95)/1000,h.getValueAtPercentile(99)/1000,h.getValueAtPercentile(99.9)/1000,h.getMaxValue()/1000);
        }
        lifecycle.print();queries.print();
        for(String required:List.of("MANUAL_CLOSE_CONFIRMED","TRIGGER_CLOSE_CONFIRMED",
                "BANKRUPTCY_INSURANCE_ADL_CONFIRMED","RISK_CONTINUATION_CONFIRMED","APPLY_FUNDING","VALKEY_QUERY_READY")) {
            if(!latency.containsKey(required) || latency.get(required).getTotalCount()==0)
                throw new IllegalStateException("measured operational coverage missing: "+required);
        }
        System.out.println("operationalCoverage=PASS");
    }
    void printComposite(long tradingItems,long tradingMessages,long tradingFills,long elapsed) {
        long commands=0;
        for(CoreMessageType type:CoreMessageType.values()) {
            var h=latency.get(type.name());
            if(h!=null && type.kind()==WireMessageKind.COMMAND)commands+=h.getTotalCount();
        }
        double seconds=Math.max(elapsed,lastTerminal.get()-measurementStart)/1e9;
        System.out.printf(Locale.ROOT,"operationalComposite=PASS terminalBusinessOperations=%d terminalCoreMessages=%d fills=%d elapsedSeconds=%.3f businessOpsPerSec=%.3f coreMessagesPerSec=%.3f fillsPerSec=%.3f unfinished=0%n",
                tradingItems+commands+extraBatchItems.get(),tradingMessages+commands,tradingFills+fills.get(),seconds,
                (tradingItems+commands+extraBatchItems.get())/seconds,(tradingMessages+commands)/seconds,(tradingFills+fills.get())/seconds);
    }
    @Override public void close() {
        try {stop();}finally {queries.close();lifecycle.close();prices.close();}
    }
}
