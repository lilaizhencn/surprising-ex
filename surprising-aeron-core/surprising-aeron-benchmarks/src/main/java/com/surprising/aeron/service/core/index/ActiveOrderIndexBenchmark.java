package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
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
    @Param({"1", "4"}) public int symbols;
    private ActiveOrderIndex index;
    private RuntimeIdentityRegistry identities;
    private final OrderRuntime[] open = new OrderRuntime[20], terminal = new OrderRuntime[20];

    @Setup public void setup() {
        identities = new RuntimeIdentityRegistry();
        index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities);
        var instruments = new CoreInstrument[symbols];
        for (int i = 0; i < symbols; i++) instruments[i] = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(Integer.toString(i + 1),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        for (int i = 0; i < open.length; i++) {
            var instrument = instruments[i % symbols];
            var order = new CoreOrderState(i + 1, ProductLine.LINEAR_PERPETUAL, i % users + 1, instrument.instrumentId(),
                    CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
            open[i] = RuntimeStateProjector.toRuntimeOrder(order, identities, instrument);
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
        if (index.count() != 0)
            throw new IllegalStateException("terminal orders remain in admission indexes");
        for (int symbol = 1; symbol <= symbols; symbol++) if (index.count(Integer.toString(symbol)) != 0)
            throw new IllegalStateException("terminal orders remain in symbol index");
        for (int user = 1; user <= users; user++) if (index.sortedIdsDescending(user).length != 0)
            throw new IllegalStateException("terminal orders remain in account index");
    }
}
