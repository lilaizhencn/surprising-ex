package com.surprising.aeron.service.execution;

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
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void scopeRefreshAndAdmissionShareOneDecodedBatch(ProductLine product) throws Exception {
        try (var fixture = new Fixture(product)) {
            fixture.setup();
            var window = new ClusterCommandWindow();
            var command = fixture.placeBatch(11, "BTC-USDT", 5000);
            var state = fixture.service.state();
            assertThat(state.prepareClusterPipelineScope(command, window)).isTrue();
            var decoded = window.decoded(command);
            assertThat(state.prepareClusterPipelineScope(command, window)).isTrue();
            assertThat(window.decoded(command)).isSameAs(decoded);
            state.applyClusterCommand(command, TIME, 1000, decoded);
            var pending = state.pendingMatching(state.matchingSequence(command.header().commandId()));
            assertThat(pending.decodedCommand()).isSameAs(decoded);
            var completed = state.completeMatchingSynchronously(pending.sequence(), TIME, 1000);
            assertThat(completed.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(TradingOrderBatchCodec.decodeResult(completed.data()).items()).hasSize(20);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void prefixCommitReturnsWhileIndependentSuffixMatcherIsStillBlocked(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product)) {
            live.setup();
            var a = live.place(11, "BTC-USDT", 1000, 80, 1, CoreOrderSide.BUY);
            var b = live.place(disjointUser(11), disjointSymbol("BTC-USDT"), 2000, 80, 1, CoreOrderSide.BUY);
            var c = live.place(11, "BTC-USDT", 3000, 79, 1, CoreOrderSide.BUY);
            live.send(a);
            var pending = live.service.state().pendingMatching(live.service.state().matchingSequence(a.header().commandId()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pending.isMatchingSubmitted() && System.nanoTime() < deadline)
                live.service.state().drainMatchingCompletions();
            assertThat(pending.isMatchingSubmitted()).isTrue();
            var field = CoreProbeState.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
            var matcher = (MatcherPipelineGroup) field.get(live.service.state());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocked = matcher.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("suffix matcher timeout"); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return 1;
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(b); live.send(c);
                assertThat(blocked.isDone()).isFalse();
                assertThat(live.responses).hasSize(1);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
            } finally { release.countDown(); }
            live.tick();
            assertThat(blocked.join()).isOne();
            assertThat(live.responses).hasSize(3);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void dependencyDrainsOnlyItsOrderedPrefixAndKeepsIndependentSuffix(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product);
             Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            var setup = live.setup(); serial.applyAll(setup); replay.applyAll(setup);
            // Real fills in both independent batches; the suffix must not enter the prefix's realtime export.
            long used = TradingDependencyMask.account(11) | TradingDependencyMask.account(disjointUser(11));
            int symbolNumber = 0;
            for (String symbol : List.of("BTC-USDT", disjointSymbol("BTC-USDT"))) {
                long seller = 100;
                while ((TradingDependencyMask.account(seller) & used) != 0) seller++;
                used |= TradingDependencyMask.account(seller);
                ContractType type = ContractType.valueOf(product.contractTypeCode());
                String asset = product == ProductLine.SPOT || type.isInverse() ? "BTC" : "USDT";
                var deposit = live.message(CoreMessageType.ADJUST_BALANCE, seller,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000)));
                var ask = live.place(seller, symbol, 100 + symbolNumber++, 80, 20, CoreOrderSide.SELL);
                for (Fixture fixture : List.of(live, serial, replay)) { fixture.apply(deposit); fixture.apply(ask); }
            }
            live.responses.clear();
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(8192, 8 * 1024 * 1024);
            CoreFaults.attachRealtime(live.service, outbox);
            var a = live.placeBatch(11, "BTC-USDT", 1000);
            var b = live.placeBatch(disjointUser(11), disjointSymbol("BTC-USDT"), 2000);
            var c = live.place(11, "BTC-USDT", 3000, 79, 1, CoreOrderSide.BUY);
            serial.apply(a); serial.apply(b); serial.apply(c);
            live.send(a); live.send(b); live.send(c);
            assertThat(live.responses).as("only the conflicting prefix has committed").hasSize(1);
            assertThat(live.service.commandWindowSize()).as("independent suffix and new command remain").isEqualTo(2);
            assertThat(live.service.state().matchingSequence(b.header().commandId())).isPositive();
            int prefixTrades = 0;
            byte[] bytes;
            while ((bytes = outbox.poll()) != null) {
                var frame = RealtimeFrameCodec.decode(bytes);
                if (frame.kind() == RealtimeFrame.Kind.TRADE) {
                    assertThat(frame.symbol()).isEqualTo("BTC-USDT");
                    prefixTrades++;
                }
            }
            assertThat(prefixTrades).isEqualTo(20);
            live.tick();
            replay.send(a); replay.send(b); replay.send(c); replay.tick();
            assertThat(live.responses).hasSize(3).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(product, live.service.captureSnapshot(880))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @Test
    void nonCrossingSharedRestingBuyerDoesNotSerializeIndependentSellers() {
        try (Fixture live = new Fixture(ProductLine.SPOT); Fixture serial = new Fixture(ProductLine.SPOT)) {
            serial.applyAll(live.setup());
            long a = 11, b = disjointUser(a), shared = b + 1;
            while ((TradingDependencyMask.account(shared) & (TradingDependencyMask.account(a) | TradingDependencyMask.account(b))) != 0) shared++;
            var deposit = live.message(CoreMessageType.ADJUST_BALANCE, shared,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 20_000)));
            live.apply(deposit); serial.apply(deposit);
            for (long user : new long[]{a, b}) {
                var base = live.message(CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100)));
                live.apply(base); serial.apply(base);
            }
            var lowA = live.place(shared, "BTC-USDT", 100, 80, 1, CoreOrderSide.BUY);
            var lowB = live.place(shared, disjointSymbol("BTC-USDT"), 200, 80, 1, CoreOrderSide.BUY);
            live.apply(lowA); live.apply(lowB); serial.apply(lowA); serial.apply(lowB);
            live.responses.clear();
            var sellA = live.place(a, "BTC-USDT", 300, 102, 1, CoreOrderSide.SELL);
            var sellB = live.place(b, disjointSymbol("BTC-USDT"), 400, 102, 1, CoreOrderSide.SELL);
            live.send(sellA); live.send(sellB);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            assertThat(live.responses).isEmpty();
            live.tick(); serial.apply(sellA); serial.apply(sellB);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(100).executedQuantitySteps()).isZero();
            assertThat(live.service.state().tradingState().order(200).executedQuantitySteps()).isZero();
        }
    }
    private static final long TIME = 1_700_000_000_000L;

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void continuousBatchesDispatchSnapshotAtCommittedBoundary(ProductLine product) throws Exception {
        try (Fixture f = new Fixture(product)) {
            f.setup();
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
            CoreFaults.attachRealtime(f.service, outbox);
            var field = SurprisingClusteredService.class.getDeclaredField("snapshotRequests");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var requests = (java.util.Queue<RealtimeFrame>) field.get(f.service);
            f.send(f.placeBatch(11, "BTC-USDT", 1000));
            requests.add(new RealtimeFrame(product, RealtimeFrame.Kind.SNAPSHOT_REQUEST,
                    11, 0, 0, TIME, 900, "", "", new byte[0]));
            assertThat(f.service.doBackgroundWork(System.nanoTime())).isZero();
            f.send(f.placeBatch(11, "BTC-USDT", 2000));
            assertThat(f.service.commandWindowSize()).isOne();
            assertThat(requests.isEmpty()).as("continuous input must not starve a read at the preceding commit boundary").isTrue();
            var pendingField = CoreProbeState.class.getDeclaredField("pendingRealtimeSnapshot");
            pendingField.setAccessible(true);
            var snapshot = (java.util.concurrent.CompletableFuture<?>) pendingField.get(f.service.state());
            assertThat(snapshot).isNotNull();
            snapshot.get(2, TimeUnit.SECONDS);
            f.send(f.cancelBatch(11, 1000));
            var frames = new ArrayList<RealtimeFrame>();
            byte[] bytes;
            while ((bytes = outbox.poll()) != null) {
                var frame = RealtimeFrameCodec.decode(bytes);
                if (frame.snapshotId() == 900) frames.add(frame);
            }
            assertThat(frames).anySatisfy(frame -> assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.SNAPSHOT_END));
            assertThat(frames.stream().filter(frame -> frame.kind() == RealtimeFrame.Kind.ORDER)).hasSize(20);
            assertThat(frames).anySatisfy(frame -> {
                assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.USER);
                assertThat(CoreStateQueryCodec.decodeUserState(frame.payload()).reservations()).hasSize(20);
            });
            f.tick();
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentTwentyItemBatchesSubmitBeforeEarlierMatcherFinishes(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            long other = disjointUser(11);
            var topology = com.surprising.aeron.service.state.LaneTopology.productionDefault();
            while (topology.accountLaneId(other) != topology.accountLaneId(11)
                    || TradingDependencyMask.account(other) == TradingDependencyMask.account(11)) other++;
            if (other != disjointUser(11)) {
                String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
                var deposit = live.message(CoreMessageType.ADJUST_BALANCE, other,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000)));
                live.apply(deposit); serial.apply(deposit); live.responses.clear();
            }
            var a = live.placeBatch(11, "BTC-USDT", 1000);
            var b = live.placeBatch(other, disjointSymbol("BTC-USDT"), 2000);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var field = CoreProbeState.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
            var matcher = (MatcherPipelineGroup) field.get(live.service.state());
            var blocked = matcher.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return 1;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                live.send(a); live.send(b);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
                var second = live.service.state().pendingMatching(live.service.state().matchingSequence(b.header().commandId()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!second.isMatchingSubmitted() && System.nanoTime() < deadline) {
                    live.service.state().drainMatchingCompletions(); Thread.onSpinWait();
                }
                assertThat(second.isMatchingSubmitted()).as("second batch must reach matcher before first completion").isTrue();
                assertThat(live.responses).isEmpty();
            } finally { release.countDown(); }
            live.tick(); assertThat(blocked.join()).isOne();
            serial.apply(a); serial.apply(b);
            assertThat(live.service.state().tradingState().orders()).isEqualTo(serial.service.state().tradingState().orders());
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            assertThat(live.hash()).isEqualTo(serial.hash());
            var ca = live.cancelBatch(11, 1000);
            var cb = live.cancelBatch(other, 2000);
            live.send(ca); live.send(cb);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            live.tick(); serial.apply(ca); serial.apply(cb);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.responses).hasSize(4).allSatisfy(response -> {
                assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(TradingOrderBatchCodec.decodeResult(response.data()).items())
                        .hasSize(20).allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
            });
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(product, live.service.captureSnapshot(800))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

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

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectedBatchAndIndependentOrdinaryOrderKeepProgressAndFunds(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -20_000)));
            live.apply(withdraw); serial.apply(withdraw); live.responses.clear();
            var rejected = live.placeBatch(11, "BTC-USDT", 1000);
            var valid = live.place(disjointUser(11), disjointSymbol("BTC-USDT"), 2000, 80, 1, CoreOrderSide.BUY);
            live.send(rejected); live.send(valid);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            live.tick(); serial.apply(rejected); serial.apply(valid);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.responses).hasSize(2);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED));
            assertThat(live.responses.getLast().status()).isEqualTo(ResponseStatus.APPLIED);
            var cancel = live.cancel(disjointUser(11), 2000);
            live.apply(cancel); serial.apply(cancel);
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancellationBehindSlowIndependentPlaceAdmissionResumesInSubmissionOrder(ProductLine product) throws Exception {
        try (Fixture f = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(f.setup());
            long other = disjointUser(11);
            long restingId = disjointOrder(101);
            CoreMessage resting = f.place(other, disjointSymbol("BTC-USDT"), restingId, 80, 1, CoreOrderSide.BUY);
            f.apply(resting); serial.apply(resting);
            CoreMessage place = f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            CoreMessage cancel = f.cancel(other, restingId);
            var runtimeField = CoreProbeState.class.getDeclaredField("runtimePlaceOrderState");
            runtimeField.setAccessible(true);
            Object runtime = runtimeField.get(f.service.state());
            var workersField = runtime.getClass().getDeclaredField("laneWorkers");
            workersField.setAccessible(true);
            Object[] workers = (Object[]) workersField.get(runtime);
            CountDownLatch entered = new CountDownLatch(workers.length), release = new CountDownLatch(1);
            Class<?> commandClass = Class.forName("com.surprising.aeron.service.state.SettlementLaneWorker$Command");
            var submit = workers[0].getClass().getDeclaredMethod("submit", commandClass);
            submit.setAccessible(true);
            try {
                for (Object worker : workers) {
                    Object command = Proxy.newProxyInstance(commandClass.getClassLoader(), new Class<?>[]{commandClass},
                            (p, method, args) -> {
                                entered.countDown();
                                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lane timeout");
                                return null;
                            });
                    submit.invoke(worker, command);
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                f.send(place); f.send(cancel);
                assertThat(f.service.commandWindowSize()).isEqualTo(2);
                assertThat(f.service.state().pendingMatching(f.service.state().matchingSequence(cancel.header().commandId()))
                        .isMatchingSubmitted()).isFalse();
            } finally { release.countDown(); }
            var pending = f.service.state().pendingMatching(f.service.state().matchingSequence(cancel.header().commandId()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pending.isMatchingSubmitted() && System.nanoTime() < deadline) {
                f.service.state().drainMatchingCompletions();
                Thread.onSpinWait();
            }
            assertThat(pending.isMatchingSubmitted()).as("deferred cancellation must resume after place admission").isTrue();
            f.tick(); serial.apply(place); serial.apply(cancel);
            assertThat(f.hash()).isEqualTo(serial.hash());
            assertThat(f.responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(product, f.service.captureSnapshot(700))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(f.hash());
            }
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
        independentFills(product, false);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentBatchFillsSettleBothSidesAndMatchSerialFunds(ProductLine product) {
        independentFills(product, true);
    }

    private void independentFills(ProductLine product, boolean batch) {
        independentFills(product, batch, false);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void collidingMakerMasksStillAllowIndependentBatchFillsAndRecovery(ProductLine product) {
        independentFills(product, true, true);
    }

    private void independentFills(ProductLine product, boolean batch, boolean collidingMakers) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            var setup = live.setup(); serial.applyAll(setup);
            long buyers = TradingDependencyMask.account(11) | TradingDependencyMask.account(disjointUser(11));
            long makerA = 77;
            while ((TradingDependencyMask.account(makerA) & buyers) != 0) makerA++;
            long occupied = buyers | TradingDependencyMask.account(makerA);
            long makerB = makerA + 1;
            if (collidingMakers) {
                while (TradingDependencyMask.account(makerB) != TradingDependencyMask.account(makerA)) makerB++;
            } else {
                while ((TradingDependencyMask.account(makerB) & occupied) != 0) makerB++;
            }
            String asset = product == ProductLine.SPOT || ContractType.valueOf(product.contractTypeCode()).isInverse()
                    ? "BTC" : "USDT";
            String secondSymbol = disjointSymbol("BTC-USDT");
            var liquidity = List.of(
                    live.message(CoreMessageType.ADJUST_BALANCE, makerA,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.message(CoreMessageType.ADJUST_BALANCE, makerB,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.place(makerA, "BTC-USDT", 201, batch ? 80 : 100, batch ? 40 : 4, CoreOrderSide.SELL),
                    live.place(makerB, secondSymbol, 202, batch ? 80 : 100, batch ? 40 : 4, CoreOrderSide.SELL));
            live.applyAll(liquidity); serial.applyAll(liquidity);
            live.responses.clear();
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
            CoreFaults.attachRealtime(live.service, outbox);
            var first = batch ? live.placeBatch(11, "BTC-USDT", 1000)
                    : live.place(11, "BTC-USDT", 301, 100, 2, CoreOrderSide.BUY);
            var second = batch ? live.placeBatch(disjointUser(11), secondSymbol, 2000)
                    : live.place(disjointUser(11), secondSymbol, disjointOrder(301), 100, 2, CoreOrderSide.BUY);
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
            assertThat(publicTrades).isEqualTo(batch ? 40 : 2);
            assertThat(executions).isEqualTo(batch ? 80 : 4);
            assertThat(begin).isOne(); assertThat(end).isOne();
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(201).executedQuantitySteps()).isEqualTo(batch ? 20 : 2);
            assertThat(live.service.state().tradingState().order(202).executedQuantitySteps()).isEqualTo(batch ? 20 : 2);
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
        CoreMessage placeBatch(long user, String symbol, long firstId) {
            List<PlaceOrderCommand> orders = new ArrayList<>();
            for (int i = 0; i < 20; i++) orders.add(new PlaceOrderCommand(firstId+i, symbol, 1,
                    CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "batch-"+(firstId+i)));
            return message(CoreMessageType.PLACE_ORDER_BATCH, user,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
        }
        CoreMessage cancelBatch(long user, long firstId) {
            List<CancelOrderCommand> orders = new ArrayList<>();
            for (int i = 0; i < 20; i++) orders.add(new CancelOrderCommand(firstId+i));
            return message(CoreMessageType.CANCEL_ORDER_BATCH, user,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders)));
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
