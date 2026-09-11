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
    void idleProbeBeforeTheFirstLogDoesNotRequireAnActivatedCommitJournal(ProductLine product) {
        try (Fixture live = new Fixture(product)) {
            live.service.ownerCompletionSignal(() -> {});
            assertThat(live.service.ownerCompletionAvailable()).isFalse();
            assertThat(live.service.pollCommands()).isZero();
            assertThat(live.service.ownerCompletionAvailable()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void deferredIngressPreservesDependentBatchOrderAndSnapshot(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var commands = List.of(live.placeBatch(11, "BTC-USDT", 71000),
                    live.cancelBatch(11, 71000));
            for (var command : commands) {
                live.service.enqueueCommittedCommand(live.session, command,
                        command.header().submittedAtEpochMillis(), 0, null);
                serial.apply(command);
            }
            assertThat(live.responses).isEmpty();
            live.tick();
            assertThat(live.responses).hasSize(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(881))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void independentPartitionSettlesRealFillWhileEarlierMatcherIsBlocked(ProductLine product) throws Exception {
        independentPartitionFill(product, false);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void independentBatchPartitionSettlesRealFillsWhileEarlierMatcherIsBlocked(ProductLine product) throws Exception {
        independentPartitionFill(product, true);
    }

    private void independentPartitionFill(ProductLine product, boolean batch) throws Exception {
        String property = "surprising.aeron.matching-engines";
        String previous = System.getProperty(property);
        System.setProperty(property, "2");
        var release = new CountDownLatch(1);
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            var state = live.service.state();
            String firstSymbol = "BTC-USDT";
            String secondSymbol = differentMatcherSymbol(state, firstSymbol);
            int firstShard = state.matchingAdapter.matcherShardId(firstSymbol);
            serial.applyAll(live.setup(secondSymbol));
            var topology = state.runtimeState.topology();
            long secondBuyer = disjointUser(11);
            while (topology.accountLaneId(secondBuyer) == topology.accountLaneId(11)
                    || TradingDependencyMask.account(secondBuyer) == TradingDependencyMask.account(11)) secondBuyer++;
            String settleAsset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            if (secondBuyer != disjointUser(11)) {
                var deposit = live.message(CoreMessageType.ADJUST_BALANCE, secondBuyer,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(settleAsset, 20_000)));
                live.apply(deposit); serial.apply(deposit);
            }
            long used = TradingDependencyMask.account(11) | TradingDependencyMask.account(secondBuyer);
            long usedLanes = topology.accountLaneMask(11) | topology.accountLaneMask(secondBuyer);
            int i = 0;
            for (String symbol : List.of(firstSymbol, secondSymbol)) {
                long seller = 100;
                while ((TradingDependencyMask.account(seller) & used) != 0
                        || (topology.accountLaneMask(seller) & usedLanes) != 0) seller++;
                usedLanes |= topology.accountLaneMask(seller);
                used |= TradingDependencyMask.account(seller);
                String asset = product == ProductLine.SPOT || ContractType.valueOf(product.contractTypeCode()).isInverse()
                        ? "BTC" : "USDT";
                var funds = live.message(CoreMessageType.ADJUST_BALANCE, seller,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000)));
                var ask = live.place(seller, symbol, 100 + i++, 80, batch ? 40 : 2, CoreOrderSide.SELL);
                live.apply(funds); serial.apply(funds); live.apply(ask); serial.apply(ask);
            }
            live.responses.clear();
            var first = batch ? live.placeBatch(11, firstSymbol, 1000)
                    : live.place(11, firstSymbol, 1000, 80, 1, CoreOrderSide.BUY);
            var second = batch ? live.placeBatch(secondBuyer, secondSymbol, 2000)
                    : live.place(secondBuyer, secondSymbol, 2000, 80, 1, CoreOrderSide.BUY);
            var entered = new CountDownLatch(1);
            var gate = state.matcherPipeline.readAtSubmissionFence(firstShard, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("partition gate timeout"); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return true;
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                long firstSequence = state.matchingSequence(first.header().commandId());
                long secondSequence = state.matchingSequence(second.header().commandId());
                assertThat(secondSequence).isGreaterThan(firstSequence);
                live.progressUntil(() -> {
                    var pending = state.pendingMatching(secondSequence);
                    if (batch) {
                        var work = state.batches.pendingOrderBatches.get(secondSequence);
                        return work != null && work.hasPendingLaneWork() && work.laneWorkComplete();
                    }
                    return pending != null && pending.settlementEvent() != null && pending.settlementEvent().complete();
                });
                if (batch) {
                    var completedBatch = state.batches.pendingOrderBatches.get(secondSequence);
                    assertThat(completedBatch.admissionOrderIndex).isNull();
                    assertThat(completedBatch.items).allSatisfy(item -> assertThat(item.laneResultPrepared).isTrue());
                    assertThat(completedBatch.preparedResponse).isNotNull()
                            .isEqualTo(TradingOrderBatchCodec.encodeResultSource(completedBatch));
                }
                // 外部通知耗尽后反复轮询仍不提前提交；解除前序阻塞后必须自行恢复推进。
                for (int spin = 0; spin < 1024; spin++) live.service.pollCommands();
                assertThat(live.service.pollCommands())
                        .as("waiting for a blocked matcher must allow the Owner idle strategy to back off")
                        .isZero();
                assertThat(gate.isDone()).isFalse();
                assertThat(state.firstPendingMatchingSequence()).isEqualTo(firstSequence);
                assertThat(live.responses).isEmpty();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(gate.join()).isTrue();
            assertThat(live.responses).hasSize(2).allSatisfy(r -> assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        } finally {
            release.countDown();
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    /** 两个订单簿可以独立撮合，同一用户不能重复花费同一份共享资金。 */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void sharedFundsRemainOneAccountAcrossMatcherPartitions(ProductLine product) throws Exception {
        String key = "surprising.aeron.matching-engines";
        String previous = System.getProperty(key);
        System.setProperty(key, "2");
        var release = new CountDownLatch(1);
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            String firstSymbol = "BTC-USDT";
            String secondSymbol = differentMatcherSymbol(live.service.state(), firstSymbol);
            serial.applyAll(live.setup(secondSymbol));
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            // 由真实产品准入计算一笔订单冻结额；不把现货名义金额套用到衍生品保证金。
            var probe = live.place(11, firstSymbol, 900, 80, 1, CoreOrderSide.BUY);
            live.apply(probe); serial.apply(probe);
            long required = live.service.state().tradingState().user(11).balances().get(asset).lockedUnits();
            assertThat(required).isPositive();
            var cancelProbe = live.cancel(11, 900);
            live.apply(cancelProbe); serial.apply(cancelProbe);
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, required - 20_000)));
            live.apply(withdraw); serial.apply(withdraw);
            live.responses.clear();
            var first = live.place(11, firstSymbol, 1000, 80, 1, CoreOrderSide.BUY);
            var second = live.place(11, secondSymbol, 2000, 80, 1, CoreOrderSide.BUY);
            var entered = new CountDownLatch(1);
            var gate = live.service.state().matcherPipeline.readAtSubmissionFence(
                    live.service.state().matchingAdapter.matcherShardId(firstSymbol), () -> {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                        return true;
                    });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                for (int i = 0; i < 20; i++) live.service.pollCommands();
                assertThat(live.responses).isEmpty();
                assertThat(live.service.state().matchingSequence(second.header().commandId()))
                        .as("shared funds must wait for the earlier account mutation").isZero();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(gate.join()).isTrue();
            assertThat(live.responses).hasSize(2);
            assertThat(live.responses.getFirst().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(live.service.state().tradingState().user(11).balances().get(asset).availableUnits()).isZero();
            assertThat(live.hash()).isEqualTo(serial.hash());
            // 取消 A 的订单后 B 可以使用释放的资金；快照恢复也必须保留这份共享余额。
            var cancel = live.cancel(11, 1000);
            live.apply(cancel); serial.apply(cancel);
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                var retry = live.place(11, secondSymbol, 3000, 80, 1, CoreOrderSide.BUY);
                live.apply(retry); serial.apply(retry);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, retry, retry.header().submittedAtEpochMillis(), 0).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash()).isEqualTo(serial.hash());
                assertThat(restored.tradingState().user(11).balances().get(asset).lockedUnits()).isEqualTo(required);
            }
        } finally {
            release.countDown();
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    /** 两个不同吃单用户仍可能结算到同一做市账户，不能仅用请求 userId 判断独立。 */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void sharedMakerAccountFencesSettlementAcrossMatcherPartitions(ProductLine product) throws Exception {
        String key = "surprising.aeron.matching-engines";
        String previous = System.getProperty(key);
        System.setProperty(key, "2");
        var release = new CountDownLatch(1);
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            String firstSymbol = "BTC-USDT";
            String secondSymbol = differentMatcherSymbol(live.service.state(), firstSymbol);
            serial.applyAll(live.setup(secondSymbol));
            String asset = product == ProductLine.SPOT || ContractType.valueOf(product.contractTypeCode()).isInverse()
                    ? "BTC" : "USDT";
            var liquidity = List.of(live.message(CoreMessageType.ADJUST_BALANCE, 77,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.place(77, firstSymbol, 100, 100, 4, CoreOrderSide.SELL),
                    live.place(77, secondSymbol, 200, 100, 4, CoreOrderSide.SELL));
            live.applyAll(liquidity); serial.applyAll(liquidity);
            var first = live.place(11, firstSymbol, 1000, 100, 2, CoreOrderSide.BUY);
            var second = live.place(disjointUser(11), secondSymbol, 2000, 100, 2, CoreOrderSide.BUY);
            live.responses.clear();
            var entered = new CountDownLatch(1);
            var gate = live.service.state().matcherPipeline.readAtSubmissionFence(
                    live.service.state().matchingAdapter.matcherShardId(firstSymbol), () -> {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                        return true;
                    });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                for (int i = 0; i < 20; i++) live.service.pollCommands();
                assertThat(live.responses).isEmpty();
                assertThat(live.service.state().matchingSequence(second.header().commandId()))
                        .as("shared counterparty funds must be settled in log order").isZero();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(gate.join()).isTrue();
            assertThat(live.responses).hasSize(2).allSatisfy(r -> assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.service.state().tradingState().order(100).executedQuantitySteps()).isEqualTo(2);
            assertThat(live.service.state().tradingState().order(200).executedQuantitySteps()).isEqualTo(2);
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        } finally {
            release.countDown();
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void nativeRejectionReleasesFundsInTheAccountLane(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product)) {
            live.setup();
            var adapter = live.service.state().matchingAdapter;
            adapter.matcherShardId("BTC-USDT");
            var symbolsField = adapter.getClass().getDeclaredField("symbols");
            symbolsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var symbols = (java.util.Map<String, Integer>) symbolsField.get(adapter);
            var registeredField = adapter.getClass().getDeclaredField("registeredSymbols");
            registeredField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var registered = (java.util.Set<Integer>) registeredField.get(adapter);
            // 故障注入使 native 返回真实的 ORDER_BOOK_ID 拒绝；不伪造成交或资金结果。
            int symbol = symbols.get("BTC-USDT");
            registered.add(symbol);
            var before = live.service.state().tradingState().user(11).balances();
            var request = live.place(11, "BTC-USDT", 1000, 80, 1, CoreOrderSide.BUY);
            try { live.apply(request); }
            finally { registered.remove(symbol); }
            assertThat(live.responses).hasSize(1);
            assertThat(live.responses.getFirst().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(live.responses.getFirst().resultCode()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
            assertThat(live.service.state().tradingState().user(11).balances()).isEqualTo(before);
            assertThat(live.service.state().tradingState().user(11).reservations()).isEmpty();
            assertThat(live.service.state().pendingMatchingCount()).isZero();
            var access = live.service.state().runtimeState.getClass().getDeclaredField("ownerLaneAccess");
            access.setAccessible(true);
            assertThat(access.getBoolean(live.service.state().runtimeState)).isFalse();
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                var retry = live.place(11, "BTC-USDT", 2000, 80, 1, CoreOrderSide.BUY);
                live.apply(retry);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, retry,
                        retry.header().submittedAtEpochMillis(), 0).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void isolatedMarginChangesStayInTheAccountLane(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            for (boolean buy : new boolean[]{false, true}) {
                var order = live.message(CoreMessageType.PLACE_ORDER, buy ? 11 : disjointUser(11),
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(buy ? 1001 : 1000,
                                "BTC-USDT", 1, buy ? CoreOrderSide.BUY : CoreOrderSide.SELL, 100, 10, false,
                                CoreMarginMode.ISOLATED, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, buy ? "margin-buy" : "margin-sell")));
                live.apply(order); serial.apply(order);
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            var before = live.service.state().tradingState().user(11);
            var field = live.service.state().runtimeState.getClass().getDeclaredField("laneWorkers");
            field.setAccessible(true);
            Object[] workers = (Object[]) field.get(live.service.state().runtimeState);
            int unrelated = (live.service.state().runtimeState.topology().accountLaneId(11) + 1) % workers.length;
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Class<?> task = Class.forName("com.surprising.aeron.service.state.SettlementLaneWorker$Command");
            var submit = workers[unrelated].getClass().getDeclaredMethod("submit", task);
            submit.setAccessible(true);
            submit.invoke(workers[unrelated], Proxy.newProxyInstance(task.getClassLoader(), new Class<?>[]{task},
                    (proxy, method, args) -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timeout");
                        return null;
                    }));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (long units : new long[]{10, -10, Long.MAX_VALUE, -Long.MAX_VALUE}) {
                    var change = live.message(CoreMessageType.ADJUST_POSITION_MARGIN, 11,
                            TradingCommandCodec.encodeAdjustPositionMargin(new AdjustPositionMarginCommand(
                                    "BTC-USDT", CoreMarginMode.ISOLATED, CorePositionSide.NET, units)));
                    live.apply(change); serial.apply(change);
                    assertThat(live.responses.getLast().commandStatus())
                            .isEqualTo(Math.abs(units) == 10 ? ResponseStatus.APPLIED : ResponseStatus.REJECTED);
                    assertThat(release.getCount()).isOne();
                }
            } finally { release.countDown(); }
            assertThat(live.service.state().tradingState().user(11).balances()).isEqualTo(before.balances());
            assertThat(live.service.state().tradingState().user(11).positions()).isEqualTo(before.positions());
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"SPOT", "OPTION"}, mode = EnumSource.Mode.EXCLUDE)
    void leverageChangesStayInTheAccountLane(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var field = live.service.state().runtimeState.getClass().getDeclaredField("laneWorkers");
            field.setAccessible(true);
            Object[] workers = (Object[]) field.get(live.service.state().runtimeState);
            int unrelated = (live.service.state().runtimeState.topology().accountLaneId(11) + 1) % workers.length;
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Class<?> task = Class.forName("com.surprising.aeron.service.state.SettlementLaneWorker$Command");
            var submit = workers[unrelated].getClass().getDeclaredMethod("submit", task);
            submit.setAccessible(true);
            submit.invoke(workers[unrelated], Proxy.newProxyInstance(task.getClassLoader(), new Class<?>[]{task},
                    (proxy, method, args) -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timeout");
                        return null;
                    }));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (long leverage : new long[]{2_000_000, 2_000_000, 1_000_000, 1_000_000_000}) {
                    var change = live.message(CoreMessageType.UPDATE_LEVERAGE, 11,
                            TradingCommandCodec.encodeUpdateLeverage(new UpdateLeverageCommand(
                                    "BTC-USDT", CoreMarginMode.CROSS, leverage)));
                    live.apply(change); serial.apply(change);
                    assertThat(live.responses.getLast().commandStatus())
                            .isEqualTo(leverage == 1_000_000_000 ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                    assertThat(release.getCount()).isOne();
                    var access = live.service.state().runtimeState.getClass().getDeclaredField("ownerLaneAccess");
                    access.setAccessible(true);
                    assertThat(access.getBoolean(live.service.state().runtimeState)).isFalse();
                }
            } finally { release.countDown(); }
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    private static String differentMatcherSymbol(TradingCoreRuntime state, String firstSymbol) {
        for (int i = 0; i < 256; i++) {
            String candidate = "PARTITION-" + i + "-USDT";
            if (state.matchingAdapter.matcherShardId(candidate) != state.matchingAdapter.matcherShardId(firstSymbol)
                    && TradingDependencyMask.account(candidate.hashCode()) != TradingDependencyMask.account(firstSymbol.hashCode()))
                return candidate;
        }
        throw new AssertionError("cannot find an independent order book partition");
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void fullIndependentWindowCompletesWithoutAnotherTimer(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            for (int i = 0; i < ClusterCommandWindow.DEFAULT_CAPACITY; i++) {
                var funds = live.message(CoreMessageType.ADJUST_BALANCE, 10000 + i,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20000)));
                live.apply(funds);
                serial.apply(funds);
            }
            var orders = new ArrayList<CoreMessage>();
            for (int i = 0; i < ClusterCommandWindow.DEFAULT_CAPACITY; i++)
                orders.add(live.place(10000 + i, "BTC-USDT", 1000 + i, 80, 1, CoreOrderSide.BUY));
            orders.forEach(live::send);
            // 满窗口本身就是确定性提交边界；后续日志回调只轮询，不再注入额外timer栅栏。
            live.progressUntil(() -> live.service.pendingCommandCount() == 0);
            serial.applyAll(orders);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().users())
                    .isEqualTo(serial.service.state().tradingState().users());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.state().snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void newAdmissionCanResumeRejectedBatchWithSuspendedCommit(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -20_000)));
            live.apply(withdraw); serial.apply(withdraw);
            live.responses.clear();
            var first = live.placeBatch(11, "BTC-USDT", 1000);
            live.send(first);
            var state = live.service.state();
            long sequence = state.matchingSequence(first.header().commandId());
            var batch = state.batches.pendingOrderBatches.get(sequence);
            var pending = state.pendingMatching.get(sequence);
            // 模拟延迟激活已开始提交、但 Lane 批量准入尚未被 owner 收集的边界。
            state.batches.beginOrderBatchCommitContext(batch, pending);
            state.suspendMatchingCommitContext(pending);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!batch.placeBatchAdmissionEvent.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(batch.placeBatchAdmissionEvent.complete()).isTrue();
            assertThat(batch.placeBatchAdmissionEvent.rejection()).isNotNull();
            var next = live.place(disjointUser(11), disjointSymbol("BTC-USDT"), 8000, 80, 1, CoreOrderSide.BUY);
            live.send(next);
            live.tick(); serial.apply(first); serial.apply(next);
            assertThat(live.responses).hasSize(2);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void batchMatcherWaitReleasesOwnerPublicationContext(ProductLine product) {
        for (boolean partialRejection : new boolean[]{false, true}) {
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                if (partialRejection) {
                    var resting = live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
                    live.apply(resting); serial.apply(resting);
                }
                var batch = TradingOrderBatchCodec.decodePlaceOrderBatch(live.placeBatch(11, "BTC-USDT", 101).payload());
                var first = live.message(CoreMessageType.PLACE_ORDER_BATCH, 11,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(
                                partialRejection ? batch.orders() : batch.orders().subList(0, 1))));
                var next = live.place(disjointUser(11), disjointSymbol("BTC-USDT"),
                        disjointOrder(8000), 80, 1, CoreOrderSide.BUY);
                var state = live.service.state();
                live.responses.clear();
                live.send(first);
                long sequence = state.matchingSequence(first.header().commandId());
                live.service.pollCommands();
                assertThat(state.commits.commitPublicationDeferred).as("batch waiting for matcher").isFalse();
                live.send(next);
                live.tick();
                assertThat(live.responses).hasSize(2).allSatisfy(response ->
                        assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items()
                        .stream().filter(item -> item.status() == ResponseStatus.REJECTED).count())
                        .isEqualTo(partialRejection ? 1 : 0);
                serial.apply(first); serial.apply(next);
                assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
                assertThat(live.hash()).isEqualTo(serial.hash());
                try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void incompleteLanePollKeepsCommitContextSuspendedForNextAdmission(ProductLine product) throws Exception {
        for (CoreMessageType type : List.of(CoreMessageType.PLACE_ORDER, CoreMessageType.CANCEL_ORDER,
                CoreMessageType.AMEND_ORDER, CoreMessageType.CANCEL_ORDER_BATCH)) {
            try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                if (type != CoreMessageType.PLACE_ORDER) {
                    var resting = type == CoreMessageType.CANCEL_ORDER_BATCH
                            ? live.placeBatch(11, "BTC-USDT", 101)
                            : live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
                    live.apply(resting); serial.apply(resting);
                }
                CoreMessage first = switch (type) {
                    case CANCEL_ORDER -> live.cancel(11, 101);
                    case CANCEL_ORDER_BATCH -> live.cancelBatch(11, 101);
                    case AMEND_ORDER -> live.message(type, 11, TradingCommandCodec.encodeAmendOrder(
                            new AmendOrderCommand(101, 102, "replace-102", 81L, 1L, CoreTimeInForce.GTC, false)));
                    default -> live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
                };
                var state = live.service.state();
                long timestamp = first.header().submittedAtEpochMillis();
                state.applyClusterCommand(first, timestamp, 0);
                long sequence = state.matchingSequence(first.header().commandId());
                var matching = CoreTestCompletion.awaitMatchingResult(state, sequence);
                assertThat(matching).isNotNull();
                var field = state.runtimeState.getClass().getDeclaredField("laneWorkers");
                field.setAccessible(true);
                Object[] workers = (Object[]) field.get(state.runtimeState);
                var entered = new CountDownLatch(workers.length);
                var release = new CountDownLatch(1);
                Class<?> task = Class.forName("com.surprising.aeron.service.state.SettlementLaneWorker$Command");
                var submit = workers[0].getClass().getDeclaredMethod("submit", task);
                submit.setAccessible(true);
                CoreMessage next = live.place(disjointUser(11), disjointSymbol("BTC-USDT"),
                        disjointOrder(8000), 80, 1, CoreOrderSide.BUY);
                try {
                    for (Object worker : workers) submit.invoke(worker, Proxy.newProxyInstance(task.getClassLoader(),
                            new Class<?>[]{task}, (proxy, method, args) -> {
                                entered.countDown();
                                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane release timeout");
                                return null;
                            }));
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(state.completeMatching(sequence, matching, timestamp, 0)).isNull();
                    assertThat(state.laneCommandContexts.required(sequence).hasCommitContext()).isTrue();
                    for (int i = 0; i < 3; i++) {
                        assertThat(state.completeMatching(sequence, matching, timestamp, 0)).isNull();
                        assertThat(state.commits.commitPublicationDeferred).as("%s pending poll", type).isFalse();
                        assertThat(state.laneCommandContexts.required(sequence).hasCommitContext()).isTrue();
                    }
                    state.applyClusterCommand(next, next.header().submittedAtEpochMillis(), 0);
                } finally { release.countDown(); }
                assertThat(CoreTestCompletion.completeMatchingSynchronously(state, sequence, timestamp, 0).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
                long nextSequence = state.matchingSequence(next.header().commandId());
                assertThat(CoreTestCompletion.completeMatchingSynchronously(state, nextSequence, next.header().submittedAtEpochMillis(), 0).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
                serial.apply(first); serial.apply(next);
                assertThat(serial.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(state.tradingState().users()).as("%s account parity", type)
                        .isEqualTo(serial.service.state().tradingState().users());
                assertThat(live.hash()).as("%s hash parity", type).isEqualTo(serial.hash());
                try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void triggerChildSurvivesLaneHandoffAndOrderIdCollision(ProductLine product) {
        for (int matchedQuantity : new int[]{0, 1, 10}) {
            boolean fill = matchedQuantity != 0;
            try (Fixture live = new Fixture(product)) {
                live.setup();
                long maker = 11, user = disjointUser(maker);
                if (product == ProductLine.SPOT) live.apply(live.message(CoreMessageType.ADJUST_BALANCE, maker,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100))));
                live.apply(live.place(maker, "BTC-USDT", 101, 100, 10, CoreOrderSide.SELL));
                live.apply(live.place(user, "BTC-USDT", 102, 100, 10, CoreOrderSide.BUY));
                // 子订单编号冲突时必须沿用准入阶段选定的编号，不能重新推导或回查 Lane。
                live.apply(live.place(user, "BTC-USDT", 18003, 80, 1, CoreOrderSide.BUY));
                if (fill) live.apply(live.place(maker, "BTC-USDT", 103, 100, matchedQuantity, CoreOrderSide.BUY));
                var trigger = new CoreTriggerOrderStateView(9001, product, user, "trigger-9001", "", "BTC-USDT",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, fill ? 100 : 110, Math.max(1, matchedQuantity),
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", "test", 0, 0, 0, 0, 1, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(trigger)));
                live.responses.clear();
                live.apply(live.message(CoreMessageType.EXECUTE_TRIGGER_ORDER, 0,
                        CoreTriggerOrderCodec.encodeExecute(9001, 1, 100, TIME)));
                assertThat(live.responses).isNotEmpty().allSatisfy(r ->
                        assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                var terminal = live.service.state().runtimeState.triggerOrder(9001);
                assertThat(terminal.status()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
                assertThat(terminal.placedOrderId()).isEqualTo(18005);
                try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
                live.apply(live.cancel(user, 18003));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentIngressContinuesWhileEarlierCommitPrefixWaitsForLanes(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var first = live.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY);
            var second = live.place(disjointUser(11), "BTC-USDT", disjointOrder(101), 81, 1, CoreOrderSide.BUY);
            var runtime = live.service.state().runtimeState;
            var field = runtime.getClass().getDeclaredField("laneWorkers");
            field.setAccessible(true);
            Object[] workers = (Object[]) field.get(runtime);
            var entered = new CountDownLatch(workers.length);
            var release = new CountDownLatch(1);
            Class<?> task = Class.forName("com.surprising.aeron.service.state.SettlementLaneWorker$Command");
            var submit = workers[0].getClass().getDeclaredMethod("submit", task);
            submit.setAccessible(true);
            try {
                for (Object worker : workers) submit.invoke(worker, Proxy.newProxyInstance(task.getClassLoader(),
                        new Class<?>[]{task}, (proxy, method, args) -> {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane release timeout");
                            return null;
                        }));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first);
                live.service.pollCommands();
                live.send(second);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
                assertThat(live.responses).isEmpty();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(live.responses).hasSize(2).allSatisfy(response ->
                    assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @Test
    void deferredSequentialBatchUsesOriginalLogTimeEvenWhenLaterIngressIsAfterExpiry() {
        try (Fixture live = new Fixture(ProductLine.LINEAR_DELIVERY);
             Fixture serial = new Fixture(ProductLine.LINEAR_DELIVERY)) {
            serial.applyAll(live.setup());
            var item = new PlaceOrderCommand(1000, "BTC-USDT", 1, CoreOrderSide.BUY, 80, 1,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                    CoreTimeInForce.GTC, false, "before-expiry");
            var batch = live.message(CoreMessageType.PLACE_ORDER_BATCH, 11,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(item))));
            var original = live.place(disjointUser(11), disjointSymbol("BTC-USDT"), 2000, 80, 1, CoreOrderSide.BUY);
            var h = original.header();
            var later = new CoreMessage(CoreMessageHeader.command(h.messageType(), h.commandId(),
                    ProductLine.LINEAR_DELIVERY, CommandSource.OPERATIONS, 990, h.sourceSequence(),
                    h.userId(), TIME + 200_000, h.correlationId()), original.payloadUnsafe());
            live.send(batch); live.send(later); live.tick();
            serial.apply(batch); serial.apply(later);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .singleElement().satisfies(result -> assertThat(result.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sameSymbolSharedMakerStillSettlesBeforeNextTakerAdmission(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var type = ContractType.valueOf(product.contractTypeCode());
            String asset = product == ProductLine.SPOT || type.isInverse() ? "BTC" : "USDT";
            var funds = live.message(CoreMessageType.ADJUST_BALANCE, 999,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000)));
            var maker = live.place(999, "BTC-USDT", 90, 80, 3, CoreOrderSide.SELL);
            live.apply(funds); live.apply(maker); serial.apply(funds); serial.apply(maker);
            live.responses.clear();
            var first = live.place(11, "BTC-USDT", 100, 80, 1, CoreOrderSide.BUY);
            var second = live.place(disjointUser(11), "BTC-USDT", 200, 80, 1, CoreOrderSide.BUY);
            live.send(first); live.send(second);
            live.progressUntil(() -> live.responses.size() == 1);
            assertThat(live.responses).hasSize(1);
            assertThat(live.service.commandWindowSize()).isOne();
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(90).executedQuantitySteps()).isEqualTo(2);
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sameSymbolIndependentOrdersRemainInFlightAndRecoverLikeSerialExecution(ProductLine product) {
        sameSymbolIndependentOrders(product, false);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sameSymbolIndependentBatchesRemainInFlightAndRecoverLikeSerialExecution(ProductLine product) {
        sameSymbolIndependentOrders(product, true);
    }

    private void sameSymbolIndependentOrders(ProductLine product, boolean batch) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product);
             Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            var setup = live.setup(); serial.applyAll(setup); replay.applyAll(setup);
            var first = batch ? live.placeBatch(11, "BTC-USDT", 100)
                    : live.place(11, "BTC-USDT", 100, 80, 1, CoreOrderSide.BUY);
            var second = batch ? live.placeBatch(disjointUser(11), "BTC-USDT", 200)
                    : live.place(disjointUser(11), "BTC-USDT", 200, 81, 1, CoreOrderSide.BUY);
            live.send(first); live.send(second);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            assertThat(live.responses).isEmpty();
            live.tick(); serial.apply(first); serial.apply(second);
            replay.send(first); replay.send(second); replay.tick();
            assertThat(live.responses).hasSize(2).allSatisfy(r ->
                    assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ordinaryCommitAndSpeculativeDispatchCannotSubmitTheSameBatchTwice(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var state = live.service.state();
            var command = live.placeBatch(11, "BTC-USDT", 8000);
            long timestamp = command.header().submittedAtEpochMillis();
            state.applyClusterCommand(command, timestamp, 0);
            long sequence = state.matchingSequence(command.header().commandId());
            var contextField = TradingCoreRuntime.class.getDeclaredField("laneCommandContexts");
            contextField.setAccessible(true);
            var contexts = (LaneCommandContextRing) contextField.get(state);
            var context = contexts.required(sequence);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!context.hasMatchingCompletion() && System.nanoTime() < deadline) state.drainMatchingCompletions();
            assertThat(context.hasMatchingCompletion()).isTrue();
            var runtimeField = TradingCoreRuntime.class.getDeclaredField("runtimeState");
            runtimeField.setAccessible(true);
            Object runtime = runtimeField.get(state);
            var workersField = runtime.getClass().getDeclaredField("laneWorkers");
            workersField.setAccessible(true);
            Object[] workers = (Object[]) workersField.get(runtime);
            var entered = new CountDownLatch(workers.length);
            var release = new CountDownLatch(1);
            Class<?> commandClass = Class.forName("com.surprising.aeron.service.state.SettlementLaneWorker$Command");
            var submit = workers[0].getClass().getDeclaredMethod("submit", commandClass);
            submit.setAccessible(true);
            try {
                for (Object worker : workers) submit.invoke(worker, Proxy.newProxyInstance(commandClass.getClassLoader(),
                        new Class<?>[]{commandClass}, (p, method, args) -> {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lane timeout");
                            return null;
                        }));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(state.completeMatching(sequence, context.takeMatchingCompletion(), timestamp, 0)).isNull();
                var batchesField = OrderBatchExecutor.class.getDeclaredField("pendingOrderBatches");
                batchesField.setAccessible(true);
                var batch = ((java.util.Map<?, ?>) batchesField.get(state.batches)).get(sequence);
                var eventsField = batch.getClass().getDeclaredField("settlementEvent");
                eventsField.setAccessible(true);
                Object dispatched = eventsField.get(batch);
                assertThat(dispatched).isNotNull();
                var dispatch = OrderedCommitCoordinator.class.getDeclaredMethod("dispatchReadyPlaceSettlements", long.class, long.class, long.class);
                dispatch.setAccessible(true);
                dispatch.invoke(state.commits, timestamp, 0L, sequence);
                assertThat(eventsField.get(batch)).as("one Lane settlement event per batch sequence").isSameAs(dispatched);
            } finally { release.countDown(); }
            assertThat(CoreTestCompletion.completeMatchingSynchronously(state, sequence, timestamp, 0).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            serial.apply(command);
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentBatchesSharingLanesKeepSettlementSequenceOrdered(ProductLine product) {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var type = ContractType.valueOf(product.contractTypeCode());
            String asset = type.isInverse() ? "BTC" : "USDT";
            var batches = new ArrayList<CoreMessage>();
            for (int i = 0; i < 64; i++) {
                String symbol = "COIN" + i + "-USDT";
                long user = 10_000 + i;
                var setup = List.of(
                        live.message(CoreMessageType.UPSERT_INSTRUMENT, 0,
                                TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(symbol, 1,
                                        type.ordinal(), "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1_000 : 1,
                                        100_000, 50_000, 0, 0, type.isDelivery() || type.isOption() ? TIME + 100_000 : 0,
                                        type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))),
                        live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                                TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                        ? new ApplyMarkPriceCommand(symbol, 1, 100, 100, 100, 1, TIME)
                                        : new ApplyMarkPriceCommand(symbol, 1, 100, 1, TIME))),
                        live.message(CoreMessageType.ADJUST_BALANCE, user,
                                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
                live.applyAll(setup); serial.applyAll(setup);
            }
            for (int i = 0; i < 64; i++) batches.add(live.placeBatch(10_000 + i, "COIN" + i + "-USDT", 10_000 + i * 20));
            live.responses.clear();
            for (var command : batches) live.send(command);
            live.tick(); serial.applyAll(batches);
            assertThat(live.responses).hasSize(64).allSatisfy(r -> assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }
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
            var completed = CoreTestCompletion.completeMatchingSynchronously(state, pending.sequence(), TIME, 1000);
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
            var field = TradingCoreRuntime.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
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
                live.progressUntil(() -> live.responses.size() == 1);
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
            // Faster polling may finish multiple prefixes per callback. Observe the actual first
            // response boundary instead of requiring a scheduling-dependent intermediate size.
            int[] prefixTrades = {0};
            live.firstResponse = () -> {
                byte[] bytes;
                while ((bytes = outbox.poll()) != null) {
                    var frame = RealtimeFrameCodec.decode(bytes);
                    if (frame.kind() == RealtimeFrame.Kind.TRADE) {
                        assertThat(frame.symbol()).isEqualTo("BTC-USDT");
                        prefixTrades[0]++;
                    }
                }
                assertThat(prefixTrades[0]).isEqualTo(20);
            };
            live.send(a); live.send(b); live.send(c);
            live.tick();
            assertThat(prefixTrades[0]).isEqualTo(20);
            replay.send(a); replay.send(b); replay.send(c); replay.tick();
            assertThat(live.responses).hasSize(3).allSatisfy(r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(880))) {
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
            f.progressUntil(() -> requests.isEmpty());
            assertThat(f.service.commandWindowSize()).isOne();
            assertThat(requests.isEmpty()).as("continuous input must not starve a read at the preceding commit boundary").isTrue();
            var readsField = TradingCoreRuntime.class.getDeclaredField("realtimeReads");
            readsField.setAccessible(true);
            var pendingField = RealtimeReadCoordinator.class.getDeclaredField("pendingRealtimeSnapshot");
            pendingField.setAccessible(true);
            var snapshot = (java.util.concurrent.CompletableFuture<?>) pendingField.get(readsField.get(f.service.state()));
            assertThat(snapshot).isNotNull();
            snapshot.get(2, TimeUnit.SECONDS);
            f.send(f.cancelBatch(11, 1000));
            f.progressUntil(() -> !f.service.state().realtimeSnapshotPending());
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
            var field = TradingCoreRuntime.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
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
            assertThat(live.service.commandWindowSize() + live.responses.size()).isEqualTo(4);
            live.tick(); serial.apply(ca); serial.apply(cb);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.responses).hasSize(4).allSatisfy(response -> {
                assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(TradingOrderBatchCodec.decodeResult(response.data()).items())
                        .hasSize(20).allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
            });
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(800))) {
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
            var field = TradingCoreRuntime.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
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
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, snapshot)) {
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
            f.progressUntil(() -> f.responses.size() == 1);
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
            assertThat(live.service.commandWindowSize() + live.responses.size()).isEqualTo(2);
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
            var runtimeField = TradingCoreRuntime.class.getDeclaredField("runtimeState");
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
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, f.service.captureSnapshot(700))) {
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
            live.progressUntil(() -> live.responses.size() == 1);
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
            // 持续Owner按命令建立确定性提交边界，两条命令各有一组完整推送。
            assertThat(begin).isEqualTo(2); assertThat(end).isEqualTo(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(201).executedQuantitySteps()).isEqualTo(batch ? 20 : 2);
            assertThat(live.service.state().tradingState().order(202).executedQuantitySteps()).isEqualTo(batch ? 20 : 2);
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
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
            f.tick();
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
    void normalizedSymbolsCompleteWithoutSchedulingTimers() {
        try (Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            f.send(f.place(11, "BTC-USDT", 101, 80, 1, CoreOrderSide.BUY));
            f.send(f.place(disjointUser(11), "btc-usdt", disjointOrder(101), 80, 1, CoreOrderSide.BUY));
            assertThat(f.service.commandWindowSize()).isEqualTo(2);
            assertThat(f.responses).isEmpty();
            int scheduled = f.scheduledTimers;
            assertThat(scheduled).isZero();
            f.tick();
            int afterTick = f.scheduledTimers;
            assertThat(afterTick).isZero();
            f.send(f.cancel(11, 101));
            assertThat(f.scheduledTimers).isEqualTo(afterTick);
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

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void blockedBatchRetainsDecodeUntilItsDependencyCommits(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var state = live.service.state();
            var gate = state.matcherPipeline.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return 1;
            });
            var first = live.place(11, "BTC-USDT", 50000, 80, 1, CoreOrderSide.BUY);
            var second = live.placeBatch(11, "BTC-USDT", 51000);
            var windowField = SurprisingClusteredService.class.getDeclaredField("commandWindow");
            windowField.setAccessible(true);
            var window = (ClusterCommandWindow) windowField.get(live.service);
            var decodedField = ClusterCommandWindow.class.getDeclaredField("decoded");
            decodedField.setAccessible(true);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                Object decoded = decodedField.get(window);
                assertThat(decoded).isNotNull();
                for (int i = 0; i < 100; i++) {
                    live.service.pollCommands();
                    assertThat(decodedField.get(window)).isSameAs(decoded);
                }
                assertThat(live.responses).isEmpty();
                assertThat(live.service.commandWindowSize()).isOne();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(gate.join()).isOne();
            assertThat(decodedField.get(window)).isNull();
            assertThat(live.responses).hasSize(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentSettlementsDispatchAheadWithoutPublishingTheSuffix(ProductLine product) throws Exception {
        try (Fixture live = new Fixture(product); Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            var state = live.service.state();
            var matcherEntered = new CountDownLatch(1);
            var releaseMatcher = new CountDownLatch(1);
            var releaseLanes = new CountDownLatch(1);
            var matcherGate = state.matcherPipeline.readAtSubmissionFence(0, () -> {
                matcherEntered.countDown();
                try { if (!releaseMatcher.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return 1;
            });
            var first = live.placeBatch(11, "BTC-USDT", 60000);
            var second = live.placeBatch(disjointUser(11), disjointSymbol("BTC-USDT"), 61000);
            try {
                assertThat(matcherEntered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                long firstSequence = state.matchingSequence(first.header().commandId());
                long secondSequence = state.matchingSequence(second.header().commandId());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((!state.pendingMatching(firstSequence).isMatchingSubmitted()
                        || !state.pendingMatching(secondSequence).isMatchingSubmitted()) && System.nanoTime() < deadline)
                    live.service.pollCommands();
                assertThat(state.pendingMatching(secondSequence).isMatchingSubmitted()).isTrue();
                var field = state.runtimeState.getClass().getDeclaredField("laneWorkers");
                field.setAccessible(true);
                Object[] workers = (Object[]) field.get(state.runtimeState);
                var entered = new CountDownLatch(workers.length);
                Class<?> task = Class.forName("com.surprising.aeron.service.state.SettlementLaneWorker$Command");
                var submit = workers[0].getClass().getDeclaredMethod("submit", task);
                submit.setAccessible(true);
                for (Object worker : workers) submit.invoke(worker, Proxy.newProxyInstance(task.getClassLoader(),
                        new Class<?>[]{task}, (p, method, args) -> {
                            entered.countDown();
                            if (!releaseLanes.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timeout");
                            return null;
                        }));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                releaseMatcher.countDown();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (state.commits.dispatchedSettlementInFlight < 2 && System.nanoTime() < deadline)
                    live.service.pollCommands();
                assertThat(state.commits.dispatchedSettlementInFlight).isEqualTo(2);
                assertThat(live.responses).isEmpty();
                assertThat(state.firstPendingMatchingSequence()).isEqualTo(firstSequence);
            } finally { releaseMatcher.countDown(); releaseLanes.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(matcherGate.join()).isOne();
            assertThat(live.responses).hasSize(2).allSatisfy(r -> assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
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
        Runnable firstResponse;
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
                            if (responses.size() == 1 && firstResponse != null) firstResponse.run();
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
        List<CoreMessage> setup() { return setup(disjointSymbol("BTC-USDT")); }
        List<CoreMessage> setup(String secondSymbol) {
            ContractType type = ContractType.valueOf(product.contractTypeCode());
            String asset = type.isInverse() ? "BTC" : "USDT";
            List<CoreMessage> commands = new ArrayList<>();
            for (String symbol : List.of("BTC-USDT", secondSymbol)) {
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
        void progressUntil(java.util.function.BooleanSupplier done) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!done.getAsBoolean() && System.nanoTime() < deadline) {
                service.onSessionOpen(null, TIME + 1);
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertThat(done.getAsBoolean()).as("bounded asynchronous progress").isTrue();
        }
        void tick() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                service.pollCommands();
                if (service.pendingCommandCount() == 0) return;
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("asynchronous commands did not complete");
        }
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
