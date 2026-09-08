package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.TradingDependencyMask;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClusterCommandPipelineTest {
    private static final long TIME = 1_700_000_000_000L;

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentCommandsEnterBeforeMatcherCompletesAndRecoverExactly(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product);
             Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            List<CoreMessage> setup = live.setup();
            serial.applyAll(setup); replay.applyAll(setup);
            long userA = 11, userB = disjointUser(userA);
            String symbolB = disjointSymbol("BTC-USDT");
            CoreMessage first = live.place(userA, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            CoreMessage second = live.place(userB, symbolB, disjointOrder(101), 80, 1, CoreOrderSide.BUY);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var field = CoreProbeState.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
            var matcher = (MatcherPipelineGroup) field.get(live.service.state());
            var blocked = matcher.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("matcher test timeout");
                } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return 1;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
                assertThat(live.responses).isEmpty();
                assertThat(live.service.doBackgroundWork(Long.MAX_VALUE)).isZero();
            } finally { release.countDown(); }
            live.tick(); assertThat(blocked.join()).isOne();
            serial.send(first); serial.tick(); serial.send(second); serial.tick();
            replay.send(first); replay.send(second); replay.tick();
            assertThat(live.responses).hasSize(2).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            assertThat(live.service.state().tradingState().order(101).createdAtEpochMillis())
                    .isEqualTo(first.header().submittedAtEpochMillis());
            assertThat(live.service.state().tradingState().order(disjointOrder(101)).createdAtEpochMillis())
                    .isEqualTo(second.header().submittedAtEpochMillis());
            assertThat(live.service.commandWindowHighWaterMark()).isEqualTo(2);
            var cancelA = live.cancel(userA, 101);
            var cancelB = live.cancel(userB, TradingCommandCodec.decodePlaceOrder(second.payloadUnsafe()).orderId());
            live.send(cancelA); live.send(cancelB);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            // A snapshot is itself a deterministic fence and includes both cancellations.
            byte[] snapshot = live.service.captureSnapshot(100);
            serial.send(cancelA); serial.tick(); serial.send(cancelB); serial.tick();
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(product, snapshot)) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
            assertThat(live.service.commandWindowSize()).isZero();
            live.tick(); // Stale/duplicate timer must not complete or execute commands twice.
            assertThat(live.responses).hasSize(4);
        }
    }

    @Test
    void sameAccountCannotSpendTheSameBalanceTwiceAcrossSymbols() {
        try (Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            f.apply(f.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", -19_900))));
            f.responses.clear();
            f.send(f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY));
            f.send(f.place(11, disjointSymbol("BTC-USDT"), disjointOrder(101), 80, 1, CoreOrderSide.BUY));
            assertThat(f.responses).hasSize(1);
            f.tick();
            assertThat(f.responses.get(0).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(f.responses.get(1).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            var balance = f.service.state().tradingState().user(11).balances().get("USDT");
            assertThat(balance.lockedUnits()).isEqualTo(80);
            assertThat(balance.availableUnits()).isEqualTo(20);
        }
    }

    @Test
    void differentSymbolsSharingMakerAccountFenceBeforeFurtherAdmission() {
        try (Fixture live = new Fixture(ProductLine.SPOT); Fixture serial = new Fixture(ProductLine.SPOT)) {
            var setup = live.setup(); serial.applyAll(setup);
            long maker = 77;
            String secondSymbol = disjointSymbol("BTC-USDT");
            List<CoreMessage> liquidity = List.of(
                    live.message(CoreMessageType.ADJUST_BALANCE, maker,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 20))),
                    live.place(maker, "BTC-USDT", 201, 100, 4, CoreOrderSide.SELL),
                    live.place(maker, secondSymbol, 202, 100, 4, CoreOrderSide.SELL));
            live.applyAll(liquidity); serial.applyAll(liquidity);
            CoreMessage first = live.place(11, "BTC-USDT", 301, 100, 2, CoreOrderSide.BUY);
            CoreMessage second = live.place(disjointUser(11), secondSymbol, disjointOrder(301), 100, 2, CoreOrderSide.BUY);
            live.responses.clear();
            live.send(first); live.send(second);
            assertThat(live.service.commandWindowSize()).isOne();
            assertThat(live.responses).hasSize(1);
            live.tick(); serial.applyAll(List.of(first, second));
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(201).executedQuantitySteps()).isEqualTo(2);
            assertThat(live.service.state().tradingState().order(202).executedQuantitySteps()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentFillsSettleBothSidesAndMatchSerialFunds(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            var setup = live.setup(); serial.applyAll(setup);
            long buyers = TradingDependencyMask.account(11) | TradingDependencyMask.account(disjointUser(11));
            long makerA = 77;
            while ((TradingDependencyMask.account(makerA) & buyers) != 0) makerA++;
            long occupied = buyers | TradingDependencyMask.account(makerA);
            long makerB = makerA + 1;
            while ((TradingDependencyMask.account(makerB) & occupied) != 0) makerB++;
            String asset = product == ProductLine.SPOT || ContractType.valueOf(product.contractTypeCode()).isInverse()
                    ? "BTC" : "USDT";
            String secondSymbol = disjointSymbol("BTC-USDT");
            var liquidity = List.of(
                    live.message(CoreMessageType.ADJUST_BALANCE, makerA,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.message(CoreMessageType.ADJUST_BALANCE, makerB,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.place(makerA, "BTC-USDT", 201, 100, 4, CoreOrderSide.SELL),
                    live.place(makerB, secondSymbol, 202, 100, 4, CoreOrderSide.SELL));
            live.applyAll(liquidity); serial.applyAll(liquidity);
            live.responses.clear();
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
            live.service.attachRealtimeForTest(outbox);
            var first = live.place(11, "BTC-USDT", 301, 100, 2, CoreOrderSide.BUY);
            var second = live.place(disjointUser(11), secondSymbol, disjointOrder(301), 100, 2, CoreOrderSide.BUY);
            live.send(first); live.send(second);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            live.tick(); serial.applyAll(List.of(first, second));
            assertThat(live.responses).hasSize(2).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            int publicTrades = 0, executions = 0, begin = 0, end = 0;
            byte[] frameBytes;
            while ((frameBytes = outbox.poll()) != null) {
                var frame = RealtimeFrameCodec.decode(frameBytes);
                switch (frame.kind()) {
                    case TRADE -> publicTrades++;
                    case EXECUTION -> executions++;
                    case COMMIT_BEGIN -> begin++;
                    case COMMIT_END -> end++;
                    default -> { }
                }
            }
            assertThat(publicTrades).isEqualTo(2);
            assertThat(executions).isEqualTo(4);
            assertThat(begin).isOne(); assertThat(end).isOne();
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(201).executedQuantitySteps()).isEqualTo(2);
            assertThat(live.service.state().tradingState().order(202).executedQuantitySteps()).isEqualTo(2);
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @Test
    void queryAndSessionCloseDrainTheFinalPartialWindowAndRetryIsIdempotent() {
        try (Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            CoreMessage place = f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            f.send(place);
            f.service.onSessionClose(f.session, TIME + 1, io.aeron.cluster.codecs.CloseReason.CLIENT_ACTION);
            assertThat(f.service.commandWindowSize()).isZero();
            long hash = f.hash();
            f.send(place); f.tick();
            assertThat(f.hash()).isEqualTo(hash);
            assertThat(f.responses).hasSize(2);
            CoreMessage cancel = f.cancel(11, 101); f.send(cancel);
            f.apply(f.message(CoreMessageType.PROBE_INCREMENT, 11, CoreProtocol.probePayload(1)));
            assertThat(f.service.commandWindowSize()).isZero();
            assertThat(f.service.state().tradingState().user(11).balances().get("USDT").lockedUnits()).isZero();
        }
    }

    @Test
    void normalizedSymbolsConflictAndTimerSchedulingIsCoalescedAcrossWaves() {
        try (Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            f.send(f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY));
            f.send(f.place(disjointUser(11), "btc-usdt", disjointOrder(101), 80, 1, CoreOrderSide.BUY));
            assertThat(f.service.commandWindowSize()).isOne();
            assertThat(f.responses).hasSize(1);
            assertThat(f.scheduledTimers).isOne();
            f.tick();
            f.send(f.cancel(11, 101));
            assertThat(f.scheduledTimers).isEqualTo(2);
            f.tick();
        }
    }

    private static long disjointUser(long user) { return disjointOrder(user); }
    private static long disjointOrder(long id) {
        for (long next = id + 1;; next++) if (TradingDependencyMask.account(next) != TradingDependencyMask.account(id)) return next;
    }
    private static String disjointSymbol(String symbol) {
        for (int i = 0;; i++) {
            String candidate = "ALT" + i + "-USDT";
            if (TradingDependencyMask.account(candidate.hashCode()) != TradingDependencyMask.account(symbol.hashCode())) return candidate;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final SurprisingClusteredService service;
        final ProductLine product;
        final List<CoreResponse> responses = new ArrayList<>();
        final ClientSession session;
        final Cluster.Role role;
        long sequence;
        int scheduledTimers;
        Fixture(ProductLine product) { this(product, Cluster.Role.LEADER); }
        Fixture(ProductLine product, Cluster.Role role) {
            this.product = product; this.role = role;
            service = new SurprisingClusteredService(product);
            session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                    new Class<?>[]{ClientSession.class}, (p, method, args) -> {
                        if (method.getName().equals("offer")) {
                            byte[] bytes = new byte[(int) args[2]];
                            ((org.agrona.DirectBuffer) args[0]).getBytes((int) args[1], bytes);
                            responses.add(CoreProtocol.decodeResponse(CoreMessageCodec.decode(bytes).payloadUnsafe()));
                            return 1L;
                        }
                        return zero(method.getReturnType());
                    });
            Cluster cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "role" -> role;
                        case "timeUnit" -> TimeUnit.MILLISECONDS;
                        case "time", "logPosition" -> TIME;
                        case "scheduleTimer" -> { scheduledTimers++; yield true; }
                        case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                        default -> zero(method.getReturnType());
                    });
            service.onStart(cluster, null);
        }
        List<CoreMessage> setup() {
            ContractType type = ContractType.valueOf(product.contractTypeCode());
            String asset = type.isInverse() ? "BTC" : "USDT";
            List<CoreMessage> commands = new ArrayList<>();
            for (String symbol : List.of("BTC-USDT", disjointSymbol("BTC-USDT"))) {
                commands.add(message(CoreMessageType.UPSERT_INSTRUMENT, 0,
                        TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(symbol, 1,
                                type.ordinal(), "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1_000 : 1,
                                100_000, 50_000, 0, 0, type.isDelivery() || type.isOption() ? TIME + 100_000 : 0,
                                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))));
                commands.add(message(CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                ? new ApplyMarkPriceCommand(symbol, 1, 100, 100, 100, 1, TIME)
                                : new ApplyMarkPriceCommand(symbol, 1, 100, 1, TIME))));
            }
            for (long user : new long[]{11, disjointUser(11)})
                commands.add(message(CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
            applyAll(commands); responses.clear(); return commands;
        }
        CoreMessage message(CoreMessageType type, long user, byte[] payload) {
            long id = ++sequence;
            return new CoreMessage(CoreMessageHeader.command(type, new UUID(990, id), product,
                    CommandSource.OPERATIONS, 990, id, user, TIME + id, id), payload);
        }
        CoreMessage place(long user, String symbol, long order, long price, long qty, CoreOrderSide side) {
            return message(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(order, symbol, 1, side, price, qty, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "order-" + order)));
        }
        CoreMessage cancel(long user, long order) {
            return message(CoreMessageType.CANCEL_ORDER, user,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(order)));
        }
        void send(CoreMessage command) {
            byte[] bytes = CoreMessageCodec.encode(command);
            service.onSessionMessage(role == Cluster.Role.LEADER ? session : null, command.header().submittedAtEpochMillis(),
                    new UnsafeBuffer(bytes), 0, bytes.length,
                    new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0).initialTermId(0).positionBitsToShift(16));
        }
        void tick() { service.onTimerEvent(SurprisingClusteredService.PIPELINE_TIMER_ID, TIME + 1); }
        void apply(CoreMessage command) { send(command); tick(); }
        void applyAll(List<CoreMessage> commands) { commands.forEach(this::apply); }
        long hash() { return service.state().tradingState().businessStateHash(); }
        public void close() { service.onTerminate(null); }
        private static Object zero(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == long.class) return 0L;
            if (type == int.class) return 0;
            return null;
        }
    }
}
