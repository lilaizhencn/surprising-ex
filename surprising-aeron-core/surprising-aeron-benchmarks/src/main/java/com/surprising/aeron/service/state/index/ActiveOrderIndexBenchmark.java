package com.surprising.aeron.service.state.index;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** One index lifecycle is OPEN insertion plus terminal removal, not a Core business operation. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class ActiveOrderIndexBenchmark {
    @Param({"1", "20"}) public int users;
    private ActiveOrderIndex index;
    private RuntimeIdentityRegistry identities;
    private final OrderRuntime[] open = new OrderRuntime[20], terminal = new OrderRuntime[20];

    @Setup public void setup() {
        identities = new RuntimeIdentityRegistry();
        index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities);
        for (int i = 0; i < open.length; i++) {
            var order = new CoreOrderState(i + 1, ProductLine.LINEAR_PERPETUAL, i % users + 1, "BTC-USDT", 1,
                    CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
            open[i] = RuntimeStateProjector.toRuntimeOrder(order, identities);
            terminal[i] = open[i].withStatus(CoreOrderStatus.CANCELED, 2);
        }
    }

    @Benchmark
    @OperationsPerInvocation(20)
    public int openThenTerminal() {
        for (var order : open) index.apply(order.orderId(), order, identities);
        for (var order : terminal) index.apply(order.orderId(), order, identities);
        return index.count();
    }

    @TearDown public void verifyEmpty() {
        if (index.count() != 0 || !index.ids("BTC-USDT").isEmpty())
            throw new IllegalStateException("terminal orders remain in admission indexes");
        for (int user = 1; user <= users; user++) if (!index.ids(user).isEmpty())
            throw new IllegalStateException("terminal orders remain in account index");
    }
}
