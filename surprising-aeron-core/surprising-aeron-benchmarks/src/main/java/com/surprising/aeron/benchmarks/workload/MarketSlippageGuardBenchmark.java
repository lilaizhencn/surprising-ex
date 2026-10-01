package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Local maker placement + taker fill; excludes account lanes, gateway and replication. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 1)
@Fork(1)
public class MarketSlippageGuardBenchmark {
    @Param({"0", "100"}) public long slippagePpm;
    private DeterministicExchangeCoreAdapter adapter;
    private long orderId;
    @Setup public void setup() { adapter = new DeterministicExchangeCoreAdapter(); }
    @Benchmark public CoreMatchingResult makerAndMarketFill() {
        adapter.place(11, new CoreMatchingOrder(++orderId, "1", CoreOrderSide.SELL,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100_000, 1));
        return adapter.place(22, new CoreMatchingOrder(++orderId, "1", CoreOrderSide.BUY,
                CoreOrderType.MARKET, CoreTimeInForce.IOC, 101_000, 1, slippagePpm));
    }
    @TearDown public void close() { adapter.close(); }
}
