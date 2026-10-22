package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.change.RuntimeOrderChangeBuffer;
import com.surprising.aeron.service.core.change.RuntimePositionChangeBuffer;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.aeron.support.ingress.CoreMessageFlyweightDecoder;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.SynchronousMatchingEngine;
import exchange.core2.core.common.*;
import exchange.core2.core.common.config.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Actual affected component paths, fixed 256 slots. These scores do not represent whole-Core allocation or throughput. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class HotCommandSlotAllocationBenchmark {
    private static final int WINDOW = 256, SYMBOL = 10_001;
    @Param({"EMPTY", "ASCII", "UNICODE"}) public String clientId;
    private final UUID commandId = new UUID(1, 2);
    private final ResolvedPlaceOrder[] resolved = new ResolvedPlaceOrder[WINDOW];
    private final PlaceOrderCommand[] decoded = new PlaceOrderCommand[WINDOW];
    private final RuntimeOrderChangeBuffer[] changes = new RuntimeOrderChangeBuffer[WINDOW];
    private final RuntimePositionChangeBuffer[] positionChanges = new RuntimePositionChangeBuffer[WINDOW];
    private final PositionRuntime[] sourcePositions = new PositionRuntime[WINDOW];
    private final OrderRuntime[] sourceOrders = new OrderRuntime[WINDOW];
    private final MatcherResult[] nativeSlots = new MatcherResult[WINDOW];
    private final java.nio.ByteBuffer[] encodingBuffers = new java.nio.ByteBuffer[WINDOW];
    private final byte[][] payloads = new byte[WINDOW][];
    private final UnsafeBuffer[] frames = new UnsafeBuffer[WINDOW];
    private final CoreMessageFlyweightDecoder frameDecoder = new CoreMessageFlyweightDecoder();
    private AccountLaneState lane;
    private SynchronousMatchingEngine matcher;
    private com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver.Context decision;
    private long retiredSequence;

    @Setup public void setup() {
        lane = new AccountLaneState(0, WINDOW); lane.bindOwner();
        matcher = new SynchronousMatchingEngine(ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.MATCHING_ONLY)
                        .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED).build())
                .performanceCfg(PerformanceConfiguration.latencyPerformanceBuilder().matchingEnginesNum(1).riskEnginesNum(0).build()).build());
        matcher.registerSymbol(CoreSymbolSpecification.builder().symbolId(SYMBOL)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR).baseCurrency(1).quoteCurrency(2)
                .baseScaleK(1).quoteScaleK(1).makerFee(0).takerFee(0).build());
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        decision = new com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver.Context(
                instrument, 0, new com.surprising.aeron.service.market.MarkPriceRuntime(0, instrument, 100, 1, 1),
                FeePolicyBook.from(java.util.Map.of(1L, new com.surprising.aeron.model.CoreFeePolicyState(
                        1, 1, 7, "1", -10, 20, 0, true, 1, 0))), 1_000,
                new TreasuryRuntime().settlementStatus(0));
        for (int index = 0; index < WINDOW; index++) {
            String client = switch (clientId) { case "EMPTY" -> ""; case "ASCII" -> "client-" + index; default -> "客户-" + index; };
            var order = new PlaceOrderCommand(index + 1, "1", CoreOrderSide.BUY, 100, 10, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, client);
            resolved[index] = new ResolvedPlaceOrder(order, instrument, 0, 100, 100, 100,
                    ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0);
            sourceOrders[index] = TradingAccountRuntime.preparedOrder(ProductLine.LINEAR_PERPETUAL, 7,
                    resolved[index], commandId, 0, 1, 1);
            decoded[index] = PlaceOrderCommand.commandSlot();
            nativeSlots[index] = MatcherResult.commandSlot();
            changes[index] = new RuntimeOrderChangeBuffer();
            positionChanges[index] = new RuntimePositionChangeBuffer();
            sourcePositions[index] = new PositionRuntime(index + 1, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                    instrument, 10, 100, 1000, 0, 200);
            payloads[index] = TradingCommandCodec.encodePlaceOrder(order);
            encodingBuffers[index] = java.nio.ByteBuffer.allocate(payloads[index].length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            frames[index] = new UnsafeBuffer(CoreMessageCodec.encode(new CoreMessage(
                    CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, new UUID(1, index + 1),
                            ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 1, index + 1, 7, 1, index + 1), payloads[index])));
        }
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long recycleAuthoritativeOrders() {
        long checksum = 0;
        long retirementFence = retiredSequence;
        for (int index = 0; index < WINDOW; index++) {
            var order = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 7, resolved[index], commandId,
                    0, 1, 1, retirementFence);
            lane.putOrder(order);
            order.applyFillInPlace(10, 0, 1, CoreOrderStatus.FILLED, 2, 2, 2);
            var removed = lane.removeOrder(order.orderId());
            if (removed != order) throw new IllegalStateException("authoritative order not detached");
            checksum += removed.executedQuantitySteps();
            removed.retireForReuse(++retiredSequence);
            lane.returnRetiredOrder(removed);
        }
        return checksum;
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long resolvePriceAndFee() {
        long checksum = 0;
        for (int index = 0; index < WINDOW; index++) {
            var target = resolved[index];
            com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver.resolveInto(
                    target, decision, 7, target.intent());
            checksum += target.matchingPriceTicks() + target.makerFeeRatePpm() + target.takerFeeRatePpm();
        }
        return checksum;
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long copyPositionAfterImages() {
        long checksum = 0;
        for (int i = 0; i < WINDOW; i++) {
            var changes = positionChanges[i];
            changes.put(i + 1, sourcePositions[i]);
            checksum += changes.get(i + 1).signedQuantitySteps();
            changes.clear();
        }
        return checksum;
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long freezeOrderAfterImages() {
        long checksum = 0;
        for (int index = 0; index < WINDOW; index++) {
            var order = sourceOrders[index];
            order.applyFillInPlace(3, 7, 0, CoreOrderStatus.OPEN, order.revision() + 1, 2, 2);
            var buffer = changes[index];
            buffer.putLive(order.orderId(), order); buffer.freezeValues();
            checksum += buffer.get(order.orderId()).executedQuantitySteps();
            buffer.clear();
        }
        return checksum;
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long matchIntoNativeSlots() {
        long fills = 0;
        for (int index = 0; index < WINDOW; index++) {
            boolean maker = (index & 1) == 0;
            var result = matcher.placeInto(nativeSlots[index], 1, index + 1, 0, 100, 100, 1,
                    maker ? OrderAction.ASK : OrderAction.BID, maker ? OrderType.GTC : OrderType.IOC, SYMBOL, maker ? 11 : 12);
            if (result.resultCode() != exchange.core2.core.common.cmd.CommandResultCode.SUCCESS)
                throw new IllegalStateException("native matching rejected");
            if (!maker) fills += result.events().getFirst().size();
        }
        for (var result : nativeSlots) result.clearCommandSlot();
        return fills;
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long decodeIntoCommandSlots(Blackhole blackhole) {
        long checksum = 0;
        for (int index = 0; index < WINDOW; index++) {
            var payload = payloads[index];
            var order = TradingCommandCodec.decodePlaceOrderInto(payload, 0, payload.length, decoded[index]);
            blackhole.consume(order.clientOrderId()); checksum += order.orderId();
        }
        return checksum;
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long encodeIntoCommandBuffers(Blackhole blackhole) {
        long checksum = 0;
        for (int index = 0; index < WINDOW; index++) {
            var output = encodingBuffers[index]; output.clear();
            TradingCommandCodec.writePlaceOrder(output, resolved[index].intent());
            blackhole.consume(output); checksum += output.getLong(4);
        }
        return checksum;
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long decodeBorrowedFrames(Blackhole blackhole) {
        long checksum = 0;
        for (int index = 0; index < WINDOW; index++) {
            var frame = frames[index];
            var message = frameDecoder.decodeBorrowed(frame, 0, frame.capacity());
            blackhole.consume(message.header()); blackhole.consume(message.payloadUnsafe());
            checksum += message.header().sourceSequence();
            message.releaseDecoderSlot();
        }
        return checksum;
    }

    @TearDown public void close() {
        if (!matcher.openOrders().isEmpty()) throw new IllegalStateException("matching left open orders");
        matcher.close();
    }
}
