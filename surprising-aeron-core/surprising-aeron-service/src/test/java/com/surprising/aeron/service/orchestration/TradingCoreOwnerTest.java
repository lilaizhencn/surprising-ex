package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.matcher.MatcherPipelineGroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class TradingCoreOwnerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class,
            names = {"SPOT", "LINEAR_PERPETUAL"})
    void ledgerCompletionResumesOrderedHeadWithoutAnotherMatcherNotification(ProductLine line) throws Exception {
        var service = new TradingOwnerTestSupport(line);
        var responses = new CopyOnWriteArrayList<byte[]>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            var register = new RegisterInstrumentCommand("1", ContractType.valueOf(line.contractTypeCode()).ordinal(),
                    "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
            onSessionMessage(service, responses, command(line, CoreMessageType.REGISTER_INSTRUMENT, 1, 0,
                    TradingCommandCodec.encodeRegisterInstrument(register)));
            onSessionMessage(service, responses, command(line, CoreMessageType.APPLY_MARK_PRICE, 2, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new com.surprising.aeron.protocol.ApplyMarkPriceCommand("1", 100, 1, 1_000))));
            for (int user = 1; user <= 2; user++) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, 2 + user, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
            }
            if (line == ProductLine.SPOT) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, 5, 2,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10))));
            }
            onSessionMessage(service, responses, command(line, CoreMessageType.PLACE_ORDER, 6, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9000, "1", CoreOrderSide.SELL,
                            100, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "ledger-maker"))));
            var core = service.state();
            var runtime = core.runtimeState;
            assertThat(core.laneTopology().accountLaneId(1))
                    .isNotEqualTo(core.laneTopology().accountLaneId(2));
            assertThat(runtime.systemLedgerStarted()).isTrue();
            assertThat(runtime.dispatchLedgerQuery(ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Ledger release timeout");
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var first = command(line, CoreMessageType.PLACE_ORDER, 7, 1,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9001, "1", CoreOrderSide.BUY,
                            100, 3, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.IOC, false, "ledger-taker-1")));
            var following = command(line, CoreMessageType.PLACE_ORDER, 8, 1,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9002, "1", CoreOrderSide.BUY,
                            100, 3, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.IOC, false, "ledger-taker-2")));
            int before = responses.size();
            service.acceptCommittedCommand(clientSession(responses), first, 2_000, 2_000);
            service.acceptCommittedCommand(clientSession(responses), following, 2_001, 2_001);
            com.surprising.aeron.service.state.MatcherSettlementEvent event = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            do {
                service.pollCommands();
                var head = core.pendingMatching.head();
                event = head == null ? null : head.settlementEvent();
                if (event != null && event.completedLaneMask() == event.requiredLaneMask()
                        && !core.hasMatchingNotifications() && !service.ownerWorkAvailable()) break;
                if (System.nanoTime() > deadline) throw new AssertionError("Owner did not await Ledger completion");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            } while (true);
            assertThat(event.complete()).isFalse();
            assertThat(responses).hasSize(before);
            release.countDown();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!event.complete()) {
                if (System.nanoTime() > deadline) throw new AssertionError("Ledger did not finish settlement");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            // Ledger publishes only a release completion and a wake-up; all Matcher/Lane queues are empty.
            assertThat(core.hasMatchingNotifications()).isFalse();
            assertThat(service.ownerCompletionAvailable()).isTrue();
            assertThat(service.ownerWorkAvailable()).isTrue();
            service.pollCommands();
            assertThat(responses.size()).isGreaterThan(before);
            finishCommands(service);
            assertThat(responses).hasSize(before + 2);
            for (int index = before; index < responses.size(); index++) {
                assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.get(index)).payload())
                        .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            assertThat(service.pendingCommandCount()).isZero();
            var state = core.tradingState();
            assertThat(state.order(9000).remainingQuantitySteps()).isEqualTo(4);
            assertThat(state.order(9001)).isNull();
            assertThat(state.order(9002)).isNull();
            assertThat(core.terminalTradeCount).isEqualTo(2);
            long funds = state.users().values().stream().mapToLong(user -> user.totalUnits("USDT")).sum();
            assertThat(funds + state.treasuryState().feeBalances().getOrDefault("USDT", 0L)).isEqualTo(20_000);
            if (line == ProductLine.SPOT) {
                assertThat(state.user(1).totalUnits("BTC")).isEqualTo(6);
                assertThat(state.user(2).totalUnits("BTC")).isEqualTo(4);
            } else {
                assertThat(state.user(1).positions().get("1").signedQuantitySteps()).isEqualTo(6);
                assertThat(state.user(2).positions().get("1").signedQuantitySteps()).isEqualTo(-6);
            }
        } finally { release.countDown(); service.onTerminate(null); }
    }

    private static CoreMessage command(ProductLine line, CoreMessageType type, long sequence,
                                       long user, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(995, sequence), line,
                CommandSource.OPERATIONS, 995, sequence, user, 1_000, sequence), payload);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("ledgerPipelineCases")
    void ledgerSettlesIndependentCommandsBeforeOwnerRetiresTheFirstResponse(
            ProductLine line, boolean batchCommands) throws Exception {
        var service = new TradingOwnerTestSupport(line);
        var responses = new CopyOnWriteArrayList<byte[]>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var inspected = new java.util.concurrent.CountDownLatch(1);
        var observedFees = new AtomicLong(-1);
        service.onStart(cluster(), null);
        try {
            long sequence = prepareLedgerPipelineMarkets(service, responses, line);
            var core = service.state();
            var runtime = core.runtimeState;
            int assetId = core.identities.assetId("USDT");
            int itemsPerCommand = batchCommands ? 2 : 1;
            var requests = new java.util.ArrayList<CoreMessage>();
            for (int taker : new int[]{1, 3}) {
                var orders = new java.util.ArrayList<PlaceOrderCommand>();
                for (int item = 0; item < itemsPerCommand; item++) {
                    orders.add(new PlaceOrderCommand(9300 + taker * 10 + item, Integer.toString((taker + 1) / 2),
                            CoreOrderSide.BUY, 100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, false, "pipeline-taker-" + taker + "-" + item));
                }
                requests.add(command(line, batchCommands ? CoreMessageType.PLACE_ORDER_BATCH : CoreMessageType.PLACE_ORDER,
                        sequence++, taker, batchCommands
                                ? TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))
                                : TradingCommandCodec.encodePlaceOrder(orders.getFirst())));
            }
            assertThat(runtime.dispatchLedgerQuery(ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Pipeline Ledger release timeout");
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            int before = responses.size();
            try (var guard = new OwnerAccountReadGuard(runtime)) {
                for (CoreMessage request : requests)
                    service.acceptCommittedCommand(clientSession(responses), request, 2_000, 2_000);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                do {
                    service.pollCommands();
                    boolean accountsComplete = true;
                    for (CoreMessage request : requests) {
                        var pending = core.pendingMatching.findByCommandId(request.header().commandId());
                        var event = pending == null ? null : pending.orderBatch == null
                                ? pending.settlementEvent() : pending.orderBatch.settlementEvent;
                        accountsComplete &= event != null && event.ready() && event.completedLaneMask() != 0
                                && (event.completedLaneMask() & event.requiredLaneMask()) == event.requiredLaneMask();
                    }
                    if (accountsComplete && !core.hasMatchingNotifications() && !service.ownerWorkAvailable()) break;
                    if (System.nanoTime() > deadline) throw new AssertionError("Independent Lane facts were not ready");
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                } while (true);
                assertThat(core.pendingMatching.size()).isEqualTo(2);
                assertThat(responses).hasSize(before);
                // This query follows the entire money prefix. Stop polling Owner before releasing
                // Ledger: both commands must settle without retiring the first response.
                assertThat(runtime.dispatchLedgerQuery(ledger -> {
                    observedFees.set(ledger.fee(assetId));
                    inspected.countDown();
                })).isTrue();
                release.countDown();
                assertThat(inspected.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(observedFees).hasValue(4L * itemsPerCommand);
                assertThat(responses).hasSize(before);
                finishCommands(service);
            }
            assertThat(responses).hasSize(before + 2);
            for (int index = 0; index < requests.size(); index++) {
                var response = CoreMessageCodec.decode(responses.get(before + index));
                assertThat(response.header().commandId()).isEqualTo(requests.get(index).header().commandId());
                var result = CoreProtocol.decodeResponse(response.payload());
                assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                if (batchCommands) assertThat(TradingOrderBatchCodec.firstNonAppliedItem(result, itemsPerCommand)).isEqualTo(-1);
            }
            assertThat(service.pendingCommandCount()).isZero();
            var state = core.tradingState();
            long fees = state.treasuryState().feeBalances().getOrDefault("USDT", 0L);
            long funds = state.users().values().stream().mapToLong(user -> user.totalUnits("USDT")).sum();
            assertThat(fees).isEqualTo(4L * itemsPerCommand);
            assertThat(funds + fees).isEqualTo(40_000);
            assertThat(core.terminalTradeCount).isEqualTo(2L * itemsPerCommand);
            for (int taker : new int[]{1, 3}) {
                assertThat(state.user(taker).reservations()).isEmpty();
                for (int item = 0; item < itemsPerCommand; item++) assertThat(state.order(9300 + taker * 10 + item)).isNull();
                assertThat(state.order(9200 + taker + 1).remainingQuantitySteps()).isEqualTo(10 - itemsPerCommand);
                if (line == ProductLine.SPOT) assertThat(state.user(taker).totalUnits("BTC")).isEqualTo(itemsPerCommand);
                else assertThat(state.user(taker).positions().get(Integer.toString((taker + 1) / 2)).signedQuantitySteps())
                        .isEqualTo(itemsPerCommand);
            }
        } finally { release.countDown(); service.onTerminate(null); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class,
            names = {"SPOT", "LINEAR_PERPETUAL"})
    void controlCommandFencesLedgerPrefillAndTheFollowingTrade(ProductLine line) throws Exception {
        var service = new TradingOwnerTestSupport(line);
        var responses = new CopyOnWriteArrayList<byte[]>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var inspected = new java.util.concurrent.CountDownLatch(1);
        var observedFees = new AtomicLong(-1);
        var observedInsurance = new AtomicLong(-1);
        service.onStart(cluster(), null);
        try {
            long sequence = prepareLedgerPipelineMarkets(service, responses, line);
            var core = service.state();
            var runtime = core.runtimeState;
            int assetId = core.identities.assetId("USDT");
            var first = pipelineTrade(line, sequence++, 1);
            var control = command(line, CoreMessageType.ADJUST_INSURANCE_FUND, sequence++, 0,
                    TradingCommandCodec.encodeAdjustInsuranceFund(
                            new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 25)));
            var following = pipelineTrade(line, sequence, 3);
            assertThat(runtime.dispatchLedgerQuery(ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Control fence release timeout");
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            int before = responses.size();
            try (var guard = new OwnerAccountReadGuard(runtime)) {
                for (var request : List.of(first, control, following))
                    service.acceptCommittedCommand(clientSession(responses), request, 2_000, 2_000);
                awaitPipelineAccounts(service, List.of(first));
                assertThat(core.pendingMatching.size()).isEqualTo(1);
                assertThat(core.pendingMatching.findByCommandId(following.header().commandId())).isNull();
                assertThat(responses).hasSize(before);
                assertThat(runtime.dispatchLedgerQuery(ledger -> {
                    observedFees.set(ledger.fee(assetId));
                    observedInsurance.set(ledger.insurance(assetId));
                    inspected.countDown();
                })).isTrue();
                release.countDown();
                assertThat(inspected.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(observedFees).hasValue(2);
                assertThat(observedInsurance).hasValue(0);
                assertThat(responses).hasSize(before);
                finishCommands(service);
            }
            assertThat(responses).hasSize(before + 3);
            var requests = List.of(first, control, following);
            for (int index = 0; index < requests.size(); index++) {
                var response = CoreMessageCodec.decode(responses.get(before + index));
                assertThat(response.header().commandId()).isEqualTo(requests.get(index).header().commandId());
                assertThat(CoreProtocol.decodeResponse(response.payload()).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            var state = core.tradingState();
            assertThat(state.treasuryState().feeBalances()).containsEntry("USDT", 4L);
            assertThat(state.treasuryState().insuranceBalances()).containsEntry("USDT", 25L);
            assertThat(state.users().values().stream().mapToLong(user -> user.totalUnits("USDT")).sum() + 4 + 25)
                    .isEqualTo(40_025);
            assertThat(service.pendingCommandCount()).isZero();
        } finally { release.countDown(); service.onTerminate(null); }
    }

    @Test
    void fullLedgerMailboxKeepsItsDispatchCursorAndRetriesWithoutNewMatcherNotifications() throws Exception {
        var line = ProductLine.LINEAR_PERPETUAL;
        var service = new TradingOwnerTestSupport(line);
        var responses = new CopyOnWriteArrayList<byte[]>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            long sequence = prepareLedgerPipelineMarkets(service, responses, line);
            var core = service.state();
            var runtime = core.runtimeState;
            assertThat(runtime.dispatchLedgerQuery(ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Full mailbox release timeout");
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            com.surprising.aeron.service.state.SystemLedgerLane.Entry query = ledger -> { };
            int queued = 0;
            while (runtime.dispatchLedgerQuery(query)) {
                if (++queued > 8_192) throw new AssertionError("Ledger mailbox did not backpressure");
            }
            assertThat(queued).isPositive();
            var first = pipelineTrade(line, sequence++, 1);
            var following = pipelineTrade(line, sequence, 3);
            int before = responses.size();
            try (var guard = new OwnerAccountReadGuard(runtime)) {
                for (var request : List.of(first, following))
                    service.acceptCommittedCommand(clientSession(responses), request, 2_000, 2_000);
                awaitPipelineAccounts(service, List.of(first, following));
                assertThat(core.pendingMatching.ledgerDispatchHead().command().header().commandId())
                        .isEqualTo(first.header().commandId());
                assertThat(core.hasMatchingNotifications()).isFalse();
                assertThat(core.hasLocalMatchingWork()).isTrue();
                assertThat(responses).hasSize(before);
                // Facts are already ready. Even commands admitted outside the independent
                // ingress path must feed the Ledger before the first response is retired.
                core.pendingMatching.findByCommandId(first.header().commandId()).clusterIndependent = false;
                core.pendingMatching.findByCommandId(following.header().commandId()).clusterIndependent = false;
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (core.pendingMatching.ledgerDispatchHead() != null) {
                    core.commits.dispatchReadyMatcherFinancialSettlements(Long.MAX_VALUE);
                    if (System.nanoTime() >= deadline) throw new AssertionError("Ledger dispatch still waits for response retirement");
                    Thread.onSpinWait();
                }
                var inspected = new java.util.concurrent.CountDownLatch(1);
                var fees = new AtomicLong(-1);
                int asset = core.identities.assetId("USDT");
                assertThat(runtime.dispatchLedgerQuery(ledger -> { fees.set(ledger.fee(asset)); inspected.countDown(); })).isTrue();
                assertThat(inspected.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fees).hasValue(4);
                assertThat(core.pendingMatching.size()).isEqualTo(2);
                assertThat(responses).hasSize(before);
                finishCommands(service);
            }
            assertThat(responses).hasSize(before + 2);
            for (int index = before; index < responses.size(); index++)
                assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.get(index)).payload()).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
            assertThat(core.pendingMatching.ledgerDispatchHead()).isNull();
            var state = core.tradingState();
            assertThat(state.treasuryState().feeBalances()).containsEntry("USDT", 4L);
            assertThat(state.users().values().stream().mapToLong(user -> user.totalUnits("USDT")).sum() + 4).isEqualTo(40_000);
            assertThat(service.pendingCommandCount()).isZero();
        } finally { release.countDown(); service.onTerminate(null); }
    }

    private static CoreMessage pipelineTrade(ProductLine line, long sequence, int taker) {
        return command(line, CoreMessageType.PLACE_ORDER, sequence, taker,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9500 + taker,
                        Integer.toString((taker + 1) / 2), CoreOrderSide.BUY, 100, 1, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.IOC,
                        false, "pipeline-boundary-" + taker)));
    }

    private static void awaitPipelineAccounts(TradingOwnerTestSupport service, List<CoreMessage> requests) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        var core = service.state();
        do {
            service.pollCommands();
            boolean ready = true;
            for (var request : requests) {
                var pending = core.pendingMatching.findByCommandId(request.header().commandId());
                var event = pending == null ? null : pending.settlementEvent();
                ready &= event != null && event.ready() && event.completedLaneMask() != 0
                        && (event.completedLaneMask() & event.requiredLaneMask()) == event.requiredLaneMask();
            }
            if (ready && !core.hasMatchingNotifications()) return;
            if (System.nanoTime() > deadline) throw new AssertionError("Pipeline account completion timeout");
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        } while (true);
    }

    private static long prepareLedgerPipelineMarkets(TradingOwnerTestSupport service,
                                                     List<byte[]> responses, ProductLine line) {
        long sequence = 1;
        for (String symbol : List.of("1", "2")) {
            onSessionMessage(service, responses, command(line, CoreMessageType.REGISTER_INSTRUMENT, sequence++, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(symbol,
                            ContractType.valueOf(line.contractTypeCode()).ordinal(), "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 10_000, 10_000, 0, -1, 0))));
            onSessionMessage(service, responses, command(line, CoreMessageType.APPLY_MARK_PRICE, sequence++, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new com.surprising.aeron.protocol.ApplyMarkPriceCommand(symbol, 100, 1, 1_000))));
        }
        for (int user = 1; user <= 4; user++) {
            onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, sequence++, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
        }
        for (int maker : new int[]{2, 4}) {
            if (line == ProductLine.SPOT) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, sequence++, maker,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10))));
            }
            onSessionMessage(service, responses, command(line, CoreMessageType.PLACE_ORDER, sequence++, maker,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9200 + maker,
                            Integer.toString(maker / 2), CoreOrderSide.SELL, 100, 10, false,
                            CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "pipeline-maker-" + maker))));
        }
        return sequence;
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> ledgerPipelineCases() {
        return java.util.stream.Stream.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL)
                .flatMap(line -> java.util.stream.Stream.of(false, true)
                        .map(batch -> org.junit.jupiter.params.provider.Arguments.of(line, batch)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("batchLedgerCompletionCases")
    void batchLedgerCompletionResumesOrderedHeadWithoutAnotherMatcherNotification(
            ProductLine line, CoreMessageType type) throws Exception {
        var service = new TradingOwnerTestSupport(line);
        var responses = new CopyOnWriteArrayList<byte[]>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            onSessionMessage(service, responses, command(line, CoreMessageType.REGISTER_INSTRUMENT, 1, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1",
                            ContractType.valueOf(line.contractTypeCode()).ordinal(), "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 10_000, 10_000, 0, -1, 0))));
            onSessionMessage(service, responses, command(line, CoreMessageType.APPLY_MARK_PRICE, 2, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new com.surprising.aeron.protocol.ApplyMarkPriceCommand("1", 100, 1, 1_000))));
            for (int user = 1; user <= 2; user++) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, 2 + user, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
            }
            if (line == ProductLine.SPOT) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, 5, 2,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 20))));
            }
            onSessionMessage(service, responses, command(line, CoreMessageType.PLACE_ORDER, 6, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9900, "1", CoreOrderSide.SELL,
                            100, 20, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "batch-ledger-maker"))));
            long sequence = 7;
            var placements = new java.util.ArrayList<PlaceOrderCommand>();
            var cancellations = new java.util.ArrayList<CancelOrderCommand>();
            var amendments = new java.util.ArrayList<AmendOrderCommand>();
            for (int item = 0; item < 20; item++) {
                long orderId = 10_000 + item;
                placements.add(new PlaceOrderCommand(orderId, "1", CoreOrderSide.BUY, 100, 1,
                        false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.IOC, false, "batch-ledger-place-" + item));
                cancellations.add(new CancelOrderCommand(orderId));
                amendments.add(new AmendOrderCommand(orderId, 11_000 + item, "batch-ledger-amend-" + item,
                        100L, 1L, CoreTimeInForce.GTC, false));
                if (type != CoreMessageType.PLACE_ORDER_BATCH) {
                    onSessionMessage(service, responses, command(line, CoreMessageType.PLACE_ORDER, sequence++, 1,
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1",
                                    CoreOrderSide.BUY, 99, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "batch-ledger-original-" + item))));
                }
            }
            byte[] payload = switch (type) {
                case PLACE_ORDER_BATCH -> TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(placements));
                case CANCEL_ORDER_BATCH -> TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(cancellations));
                case AMEND_ORDER_BATCH -> TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(amendments));
                default -> throw new AssertionError(type);
            };
            var core = service.state();
            var runtime = core.runtimeState;
            assertThat(runtime.systemLedgerStarted()).isTrue();
            assertThat(core.laneTopology().accountLaneId(1)).isNotEqualTo(core.laneTopology().accountLaneId(2));
            assertThat(runtime.dispatchLedgerQuery(ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Batch Ledger release timeout");
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            int before = responses.size();
            service.acceptCommittedCommand(clientSession(responses), command(line, type, sequence++, 1, payload), 2_000, 2_000);
            service.acceptCommittedCommand(clientSession(responses), command(line, CoreMessageType.PLACE_ORDER, sequence, 1,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9800, "1", CoreOrderSide.BUY,
                            98, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.IOC, false, "batch-ledger-following"))), 2_001, 2_001);
            com.surprising.aeron.service.state.MatcherSettlementEvent event;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            do {
                service.pollCommands();
                var head = core.pendingMatching.head();
                var batch = head == null ? null : head.orderBatch;
                event = batch == null ? null : batch.itemSettlementEvent != null
                        ? batch.itemSettlementEvent : batch.settlementEvent;
                if (event != null && event.requiredLaneMask() != 0
                        && (event.completedLaneMask() & event.requiredLaneMask()) == event.requiredLaneMask()
                        && !core.hasMatchingNotifications()
                        && (type != CoreMessageType.CANCEL_ORDER_BATCH || !service.ownerWorkAvailable())) break;
                if (System.nanoTime() > deadline) throw new AssertionError("Batch Owner did not await Ledger completion");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            } while (true);
            assertThat(event.complete()).isFalse();
            assertThat(responses).hasSize(before);
            release.countDown();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!event.complete()) {
                if (System.nanoTime() > deadline) throw new AssertionError("Ledger did not finish batch settlement");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertThat(service.ownerCompletionAvailable()).isTrue();
            assertThat(service.ownerWorkAvailable()).isTrue();
            finishCommands(service);
            assertThat(responses).hasSize(before + 2);
            var batchResponse = CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.get(before)).payload());
            assertThat(batchResponse.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(batchResponse, 20)).isEqualTo(-1);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.get(before + 1)).payload())
                    .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(service.pendingCommandCount()).isZero();
            var state = core.tradingState();
            assertThat(state.user(1).reservations()).isEmpty();
            assertThat(state.user(1).balances().get("USDT").lockedUnits()).isEqualTo(
                    line == ProductLine.SPOT || type == CoreMessageType.CANCEL_ORDER_BATCH ? 0 : 200);
            assertThat(state.order(9800)).isNull();
            for (int item = 0; item < 20; item++) {
                assertThat(state.order(10_000 + item)).isNull();
                assertThat(state.order(11_000 + item)).isNull();
            }
            long fees = state.treasuryState().feeBalances().getOrDefault("USDT", 0L);
            long funds = state.users().values().stream().mapToLong(user -> user.totalUnits("USDT")).sum();
            assertThat(funds + fees).isEqualTo(20_000);
            if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
                assertThat(fees).isZero();
                assertThat(core.terminalTradeCount).isZero();
                assertThat(state.order(9900).remainingQuantitySteps()).isEqualTo(20);
            } else {
                assertThat(fees).isEqualTo(40);
                assertThat(core.terminalTradeCount).isEqualTo(20);
                assertThat(state.order(9900)).isNull();
                if (line == ProductLine.SPOT) {
                    assertThat(state.user(1).totalUnits("BTC")).isEqualTo(20);
                    assertThat(state.user(2).totalUnits("BTC")).isZero();
                } else {
                    assertThat(state.user(1).positions().get("1").signedQuantitySteps()).isEqualTo(20);
                    assertThat(state.user(2).positions().get("1").signedQuantitySteps()).isEqualTo(-20);
                }
            }
        } finally { release.countDown(); service.onTerminate(null); }
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> batchLedgerCompletionCases() {
        return java.util.stream.Stream.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL)
                .flatMap(line -> java.util.stream.Stream.of(CoreMessageType.PLACE_ORDER_BATCH,
                        CoreMessageType.CANCEL_ORDER_BATCH, CoreMessageType.AMEND_ORDER_BATCH)
                        .map(type -> org.junit.jupiter.params.provider.Arguments.of(line, type)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void terminalPreparationFailureRollsBackOnLaneAndRetainsRejection(boolean capacityFailure) throws Exception {
        var service = service();
        service.onStart(cluster(), null);
        var owner = service.state();
        var runtime = owner.runtimeState;
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(owner, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            var epoch = runtime.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long before = epoch.getLong(runtime);
            var request = command(CoreMessageType.ADJUST_BALANCE, 2, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            CoreResponse result;
            runtime.enterAsynchronousCommandScope();
            try {
                assertThat(owner.applyDecodedCommand(request, 2000, 2000, null, false)).isNull();
                // The retained funds-identity lookup precedes Account Lane work.
                // Inject only after that lookup has installed the actual continuation.
                long preparationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!owner.directCommand.hasControlWork()) {
                    assertThat(owner.pollDirectCommand()).isNull();
                    if (System.nanoTime() >= preparationDeadline)
                        throw new AssertionError("funds preflight did not reach Account Lane work");
                    Thread.onSpinWait();
                }
                var business = owner.directCommand.controlWork();
                owner.directCommand.replaceControlWork(() -> {
                    if (!business.getAsBoolean()) return false;
                    if (capacityFailure) {
                        // Overflow only: entries must be rejected and cleared before they can be dispatched.
                        owner.admissions.queuedMatching.addAll(java.util.Collections.nCopies(
                                owner.pendingMatching.capacity() + 1, null));
                    } else {
                        owner.resultBuilder.commandChangedOrderIds = new java.util.AbstractList<Long>() {
                            public int size() { return 1; }
                            public Long get(int index) {
                                if (StackWalker.getInstance().walk(frames -> frames.anyMatch(
                                        frame -> (frame.getMethodName().equals("stampChangedOrdersByLane")
                                                || frame.getMethodName().equals("validateStampInputs")))))
                                    throw new IllegalStateException("injected terminal metadata failure");
                                return Long.MAX_VALUE;
                            }
                        };
                    }
                    return true;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do {
                    result = owner.pollDirectCommand();
                    if (System.nanoTime() > deadline) throw new AssertionError("finalization rollback timed out");
                } while (result == null);
            } finally { runtime.exitAsynchronousCommandScope(); }
            assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(result.resultCode()).isEqualTo(capacityFailure
                    ? CoreResultCode.MATCHING_BACKPRESSURE : CoreResultCode.INVALID_COMMAND);
            assertThat(CoreTestCompletion.userState(owner, 1001).balances())
                    .filteredOn(balance -> balance.asset().equals("USDT")).singleElement().satisfies(balance -> {
                        assertThat(balance.availableUnits()).isEqualTo(10000);
                        assertThat(balance.lockedUnits()).isZero();
                    });
            assertThat(epoch.getLong(runtime)).isEqualTo(before);
            assertThat(owner.admissions.queuedMatching).isEmpty();
            var duplicate = CoreTestCompletion.applyAsynchronously(owner, request);
            assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(duplicate.resultCode()).isEqualTo(result.resultCode());
        } finally { service.onTerminate(null); }
    }

    @Test
    void algoUpdatesStayOnAccountLaneAndPublishRevisions() throws Exception {
        var service = service();
        var responses = new CopyOnWriteArrayList<byte[]>();
        service.onStart(cluster(), null);
        try {
            var runtime = service.state().runtimeState;
            var epoch = runtime.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long before = epoch.getLong(runtime);
            for (int revision = 1; revision <= 3; revision++) {
                var algo = new com.surprising.aeron.protocol.CoreAlgoOrderView(501, 1001, "algo-client", "1", 0,
                        CoreOrderSide.BUY, 0, 100, 10, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                        false, false, CoreTimeInForce.IOC, 0, 0, "",
                        1, 1, 0, 1, revision, revision, List.of(), 0, 0, 0);
                onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER,
                        revision, 1001, com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(algo)));
                finishCommands(service);
                assertThat(CoreTestCompletion.algoState(service.state(), 1001, 501).revision()).isEqualTo(revision);
                assertThat(CoreTestCompletion.algoState(service.state(), 1001, 501).userId()).isEqualTo(1001);
            }
            assertThat(epoch.getLong(runtime)).isEqualTo(before);
            long revisionBeforeFailure = runtime.revision();
            runtime.setMetadata(ProductLine.SPOT, Long.MAX_VALUE);
            var next = new com.surprising.aeron.protocol.CoreAlgoOrderView(501, 1001, "algo-client", "1", 0,
                    CoreOrderSide.BUY, 0, 100, 10, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                    false, false, CoreTimeInForce.IOC, 0, 0, "",
                    1, 1, 0, 1, 4, 4, List.of(), 0, 0, 0);
            byte[] payload = com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(next);
            onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER, 4, 1001, payload));
            finishCommands(service);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getLast()).payloadUnsafe()).status())
                    .isEqualTo(ResponseStatus.REJECTED);
            assertThat(CoreTestCompletion.algoState(service.state(), 1001, 501).revision()).isEqualTo(3);
            assertThat(runtime.revision()).isEqualTo(Long.MAX_VALUE);
            runtime.setMetadata(ProductLine.SPOT, revisionBeforeFailure);
            onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER, 5, 1001, payload));
            finishCommands(service);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getLast()).payloadUnsafe()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.algoState(service.state(), 1001, 501).revision()).isEqualTo(4);
            assertThat(epoch.getLong(runtime)).isEqualTo(before);

        } finally { service.onTerminate(null); }
    }

    @Test
    void ownerMetadataCommandsDoNotHandoffAccountLanes() throws Exception {
        var service = service();
        var responses = new CopyOnWriteArrayList<byte[]>();
        service.onStart(cluster(), null);
        try {
            var runtime = service.state().runtimeState;
            var epoch = runtime.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long before = epoch.getLong(runtime);
            onSessionMessage(service, responses,
                    command(CoreMessageType.PROBE_INCREMENT, 1, 1001, CoreProtocol.probePayload(3)));
            finishCommands(service);
            onSessionMessage(service, responses,
                    command(CoreMessageType.VERIFY_STATE_HASH, 2, 1001, new byte[0]));
            finishCommands(service);
            var timer = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(
                    com.surprising.aeron.protocol.CoreCancelAllAfterAction.SET, 1001, "1",
                    1000, 2000, 0, 0, 0, 1000);
            onSessionMessage(service, responses, command(CoreMessageType.UPDATE_CANCEL_ALL_AFTER, 3, 1001,
                    com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeCommand(timer)));
            finishCommands(service);
            assertThat(epoch.getLong(runtime)).isEqualTo(before);
            assertThat(service.state().probeValue()).isEqualTo(3);
            assertThat(runtime.cancelAllAfterTimer(new com.surprising.aeron.service.state.model.CoreCancelAllAfterKey(
                    1001, "1")).revision()).isEqualTo(1);
            assertThat(service.state().appliedCommandCount()).isEqualTo(3);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void preflightQueryWaitsForLaneOnLoggedCallbacksWithoutOwnerAccountReads() throws Exception {
        var service = service();
        var responses = new CopyOnWriteArrayList<byte[]>();
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            var runtime = service.state().runtimeState;
            var entered = new java.util.concurrent.CountDownLatch(1);
            long mask = runtime.topology().accountLaneMask(1001);
            assertThat(runtime.dispatchAccountQuery(mask, lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane release timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long handoff = runtime.laneHandoffEpoch();
            var intent = new PlaceOrderCommand(910, "1", CoreOrderSide.BUY, 100, 3, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "preflight-910");
            var request = new CoreMessage(CoreMessageHeader.query(CoreMessageType.ORDER_PREFLIGHT_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 1000, 9),
                    TradingCommandCodec.encodePlaceOrder(intent));
            try (var guard = new OwnerAccountReadGuard(runtime)) {
                byte[] encoded = CoreMessageCodec.encode(request);
                service.onSessionMessage(clientSession(responses), 1000, new UnsafeBuffer(encoded), 0,
                        encoded.length, aeronHeader());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (service.state().querySequence(request.header().commandId()) == 0 && System.nanoTime() < deadline)
                    service.pollCommands();
                assertThat(service.state().querySequence(request.header().commandId())).isNotZero();
                assertThat(responses).isEmpty();
                assertThat(release.getCount()).isOne();
                release.countDown();
                finishCommands(service);
                assertThat(responses).hasSize(1);
                var response = CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getFirst()).payloadUnsafe());
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                assertThat(response.data()).isEqualTo(com.surprising.aeron.protocol.CoreOrderPreflightCodec.encode(
                        new com.surprising.aeron.protocol.CoreOrderPreflightView("USDT", 300)));
                assertThat(runtime.laneHandoffEpoch()).isEqualTo(handoff);
                service.state().assertClusterCallbackComplete();
            }
        } finally { release.countDown(); service.onTerminate(null); }
    }

    @Test
    void bookQueryDoesNotWaitForBlockedMatcherAndCompletesOnLaterLoggedCallback() throws Exception {
        var service = service();
        var responses = new CopyOnWriteArrayList<byte[]>();
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            var entered = new java.util.concurrent.CountDownLatch(1);
            long token = service.state().matcherPipeline.submitControl(0, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher release timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                return Boolean.TRUE;
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.BOOK_STATE_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 0, 1000, 9),
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOrderBookQuery(
                            new com.surprising.aeron.protocol.CoreOrderBookQuery("1", 10)));
            byte[] encoded = CoreMessageCodec.encode(query);
            service.onSessionMessage(clientSession(responses), 1000, new UnsafeBuffer(encoded), 0,
                    encoded.length, aeronHeader());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.state().querySequence(query.header().commandId()) == 0 && System.nanoTime() < deadline) {
                service.pollCommands();
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertThat(service.state().querySequence(query.header().commandId())).isNotZero();
            assertThat(responses).isEmpty();
            assertThat(release.getCount()).isOne();
            release.countDown();
            Object result;
            do { result = service.state().matcherPipeline.pollControl(0, token); Thread.onSpinWait(); }
            while (result == null && System.nanoTime() < deadline);
            assertThat(result).isEqualTo(Boolean.TRUE);
            finishCommands(service);
            assertThat(responses).hasSize(1);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getFirst()).payloadUnsafe()).status())
                    .isEqualTo(ResponseStatus.OK);
        } finally { release.countDown(); service.onTerminate(null); }
    }


    @Test
    void emptyPollingChecksSilentFailureWithinOneMillisecondAndCommandsNeverSkipHealth() throws Exception {
        var service = service();
        service.onStart(cluster(), null);
        var state = service.state();
        var field = CoreSnapshotLifecycle.class.getDeclaredField("snapshotAuditFailure");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var failureSlot = (AtomicReference<RuntimeException>) field.get(state.snapshots);
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(state, timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            assertThat(state.hasMatchingNotifications(now)).isFalse();
            var failure = new IllegalStateException("silent snapshot audit failure");
            failureSlot.set(failure);
            assertThat(state.hasMatchingNotifications(now + 999_999)).isFalse();
            assertThatThrownBy(() -> state.apply(timerInstrument())).isSameAs(failure);
            assertThatThrownBy(() -> state.hasMatchingNotifications(now + 1_000_000)).isSameAs(failure);
        } finally {
            failureSlot.set(null);
            service.onTerminate(null);
        }
    }

    @Test
    void stageProgressAndReentrantBackgroundDeferSnapshotUntilSettlementCompletes() throws Exception {
        var service = service();
        var responses = new CopyOnWriteArrayList<byte[]>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var idle = new org.agrona.concurrent.IdleStrategy() {
            public void idle(int work) { throw new AssertionError("command callback must not idle"); }
            public void idle() { idle(0); }
            public void reset() { }
            public String alias() { return "nonblocking-callback"; }
        };
        Cluster delegate = cluster();
        Cluster controlled = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(),
                new Class<?>[]{Cluster.class}, (proxy, method, arguments) ->
                        method.getName().equals("idleStrategy") ? idle : method.invoke(delegate, arguments));
        service.onStart(controlled, null);
        var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
        CoreFaults.attachRealtime(service, outbox);
        try {
            CoreTestCompletion.applyAsynchronously(service.state(), timerInstrument());
            CoreTestCompletion.applyAsynchronously(service.state(), command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
            var field = TradingCoreRuntime.class.getDeclaredField("matcherPipeline");
            field.setAccessible(true);
            var pipeline = (MatcherPipelineGroup) field.get(service.state());
            var blocked = pipeline.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test matcher timeout");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return 1;
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var requests = service.snapshotRequests();
            assertThat(requests.offer(new com.surprising.aeron.protocol.RealtimeFrame(ProductLine.SPOT,
                    com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_REQUEST,
                    1001, 0, 0, 0, 91, "", "", new byte[0]))).isTrue();
            var place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(904, "1",
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "progress")));
            byte[] encoded = CoreMessageCodec.encode(place);
            service.onSessionMessage(clientSession(responses), 1_000, new UnsafeBuffer(encoded), 0,
                    encoded.length, aeronHeader());
            for (int tick = 0; tick < 4; tick++) {
                service.pollCommands();
                assertThat(service.doBackgroundWork(System.nanoTime())).isZero();
                assertThat(responses).isEmpty();
                assertThat(service.state().realtimeSnapshotPending()).isFalse();
            }
            assertThat(blocked.isDone()).isFalse();
            release.countDown();
            finishCommands(service);
            assertThat(blocked.join()).isEqualTo(1);
            assertThat(responses).hasSize(1);
            service.state().assertClusterCallbackComplete();
            var snapshotFrames = drainRealtime(outbox);
            // Read dispatch is now allowed at the completed window boundary, but never
            // during the reentrant idle callbacks above while settlement is pending.
            assertThat(requests.isEmpty()).isTrue();
            long snapshotDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.state().realtimeSnapshotPending() && System.nanoTime() < snapshotDeadline) {
                service.doBackgroundWork(System.nanoTime());
                Thread.yield();
            }
            assertThat(service.state().realtimeSnapshotPending()).isFalse();
            // The dispatched realtime read also owns Lane mailbox work. Inspect the full
            // fenced state only after that read has completed, not merely the trade response.
            assertThat(service.state().tradingState().user(1001).balances().get("USDT").lockedUnits())
                    .isEqualTo(2_000);
            snapshotFrames.addAll(drainRealtime(outbox));
            assertThat(snapshotFrames).anySatisfy(frame -> {
                assertThat(frame.kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.USER);
                var user = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeUserState(frame.payload());
                assertThat(user.balances().getFirst().lockedUnits()).isEqualTo(2_000);
            });
            try (var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, service.state().snapshot())) {
                assertThat(restored.tradingState().businessStateHash())
                        .isEqualTo(service.state().tradingState().businessStateHash());
            }
        } finally {
            release.countDown();
            service.onTerminate(null);
        }
    }


    @Test
    void exhaustedRealtimeOutboxDoesNotRejectOrLeaveUnsettledBusinessCommands() {
        var service=service();service.onStart(cluster(),null);
        var outbox=new com.surprising.aeron.client.RealtimeOutbox(2,128);CoreFaults.attachRealtime(service, outbox);
        try {
            replayWithoutSession(service,timerInstrument());
            replayWithoutSession(service,command(CoreMessageType.ADJUST_BALANCE,1,1001,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",10000))));
            replayWithoutSession(service,command(CoreMessageType.PLACE_ORDER,2,1001,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(20000,"1",
                    CoreOrderSide.BUY,1000,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,
                    CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"overflow-order"))));
            replayWithoutSession(service,command(CoreMessageType.CANCEL_ORDER,3,1001,
                TradingCommandCodec.encodeCancelOrder(new com.surprising.aeron.protocol.CancelOrderCommand(20000))));
            service.state().assertClusterCallbackComplete();
            var user=service.state().tradingState().users().get(1001L);
            assertThat(user.balances().get("USDT").availableUnits()).isEqualTo(10000);
            assertThat(user.balances().get("USDT").lockedUnits()).isZero();
            assertThat(user.reservations()).isEmpty();
            assertThat(outbox.droppedBatches()).isPositive();
        } finally {service.onTerminate(null);}
    }

    @Test
    void realtimePublishesCommittedBalancesAndOrdersAndStopsOnFollower() {
        var service=service(); service.onStart(cluster(),null);
        var outbox=new com.surprising.aeron.client.RealtimeOutbox(1024,1_048_576);
        CoreFaults.attachRealtime(service, outbox);
        try {
            replayWithoutSession(service,timerInstrument());
            replayWithoutSession(service,command(CoreMessageType.ADJUST_BALANCE,1,1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",10_000))));
            var frames=drainRealtime(outbox);
            assertThat(frames).anySatisfy(frame -> {
                assertThat(frame.kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.BALANCE);
                var view=com.surprising.aeron.protocol.CoreStateQueryCodec.decodeUserState(frame.payload());
                assertThat(view.balances().getFirst().availableUnits()).isEqualTo(10_000);
            });
            replayWithoutSession(service,command(CoreMessageType.PLACE_ORDER,2,1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(20_000,"1",
                    CoreOrderSide.BUY,1_000,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,
                    CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"push-order"))));
            assertThat(drainRealtime(outbox)).anySatisfy(frame -> {
                assertThat(frame.kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.ORDER);
                assertThat(frame.userId()).isEqualTo(1001);
            });
            service.state().captureRealtimeSnapshot(1001,19,7,1234);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(service.state().pollRealtimeSnapshot()==0 && System.nanoTime()<deadline)Thread.onSpinWait();
            frames=drainRealtime(outbox);
            assertThat(frames.getFirst().kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_BEGIN);
            assertThat(frames.getLast().kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_END);
            for(int n=0;n<frames.size();n++) assertThat(frames.get(n).ordinal()).isEqualTo(n);
            service.onRoleChange(Cluster.Role.FOLLOWER);
            replayWithoutSession(service,command(CoreMessageType.ADJUST_BALANCE,3,1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1))));
            assertThat(outbox.poll()).isNull();
        } finally {service.onTerminate(null);}
    }

    private static java.util.List<com.surprising.aeron.protocol.RealtimeFrame> drainRealtime(
            com.surprising.aeron.client.RealtimeOutbox outbox) {
        var frames=new java.util.ArrayList<com.surprising.aeron.protocol.RealtimeFrame>();
        byte[] bytes; while((bytes=outbox.poll())!=null) frames.add(com.surprising.aeron.protocol.RealtimeFrameCodec.decode(bytes));
        return frames;
    }

    @Test
    void followerReplayAndLeaderCompleteEveryCallbackWithoutBackgroundPumps() {
        long expectedHash = 0;
        for (Cluster.Role role : new Cluster.Role[]{Cluster.Role.LEADER, Cluster.Role.FOLLOWER}) {
            TradingOwnerTestSupport service = service();
            service.onStart(cluster(role), null);
            try {
                replayWithoutSession(service, timerInstrument());
                replayWithoutSession(service, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
                for (int cycle = 0; cycle < 256; cycle++) {
                    long orderId = 20_000 + cycle;
                    replayWithoutSession(service, command(CoreMessageType.PLACE_ORDER, 2 + cycle * 2, 1001,
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1",
                                    CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                                    CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                                    false, "replay-" + orderId))));
                    assertThat(service.state().tradingState().order(orderId).updatedAtEpochMillis()).isEqualTo(1234);
                    replayWithoutSession(service, command(CoreMessageType.CANCEL_ORDER, 3 + cycle * 2, 1001,
                            TradingCommandCodec.encodeCancelOrder(new com.surprising.aeron.protocol.CancelOrderCommand(orderId))));
                    if (role == Cluster.Role.FOLLOWER) Thread.yield();
                }
                var balance = service.state().tradingState().users().get(1001L).balances().get("USDT");
                assertThat(balance.availableUnits()).isEqualTo(10_000);
                assertThat(balance.lockedUnits()).isZero();
                assertThat(service.state().tradingState().orders()).isEmpty();
                long hash = service.state().tradingState().businessStateHash();
                if (role == Cluster.Role.LEADER) expectedHash = hash;
                else assertThat(hash).isEqualTo(expectedHash);
                try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, service.captureSnapshot(1000))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(hash);
                }
                assertThat(service.doBackgroundWork(Long.MAX_VALUE)).isZero();
            } finally {
                service.onTerminate(null);
            }
        }
    }

    private static void replayWithoutSession(TradingOwnerTestSupport service, CoreMessage message) {
        byte[] encoded = CoreMessageCodec.encode(message);
        service.onSessionMessage(null, 1234, new UnsafeBuffer(encoded), 0, encoded.length, aeronHeader());
        finishCommands(service);
        service.state().assertClusterCallbackComplete();
    }

    @Test
    void asynchronousBookQueryIsCollectedBeforeCallbackReturnsEvenWithoutSession() {
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            replayWithoutSession(service, timerInstrument());
            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.BOOK_STATE_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 0, 1000, 9),
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOrderBookQuery(
                            new com.surprising.aeron.protocol.CoreOrderBookQuery("1", 10)));
            replayWithoutSession(service, query);
            assertThat(service.state().querySequence(query.header().commandId())).isZero();
            assertThat(service.doBackgroundWork(0)).isZero();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void egressRetriesOnlyInsideLogCallbackAndBackgroundNeverOffersOrCloses() {
        TradingOwnerTestSupport service = service();
        AtomicInteger offers = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ClientSession session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 91L;
                    case "isClosing" -> false;
                    case "offer" -> offers.incrementAndGet() < 3 ? io.aeron.Publication.BACK_PRESSURED : 1L;
                    case "close" -> { closes.incrementAndGet(); yield null; }
                    default -> defaultValue(method.getReturnType());
                });
        service.onStart(cluster(), null);
        try {
            CoreMessage command = command(CoreMessageType.PROBE_INCREMENT, 1, 1001, CoreProtocol.probePayload(3));
            byte[] encoded = CoreMessageCodec.encode(command);
            service.onSessionMessage(session, 1234, new UnsafeBuffer(encoded), 0, encoded.length, aeronHeader());
            finishCommands(service);
            assertThat(offers.get()).isBetween(1, 2);
            int before = offers.get();
            service.doBackgroundWork(0);
            assertThat(offers).hasValue(before);
            while (offers.get() < 3) service.pollCommands();
            assertThat(offers).hasValue(3);
            assertThat(closes).hasValue(0);
            assertThat(service.state().probeValue()).isEqualTo(3);
            assertThat(service.state().appliedCommandCount()).isEqualTo(1);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void unfinishedCallbackIsAnAgentTerminationNotARecoverableBusinessRejection() {
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            preparePendingPlace(service.state(), 30_001);
            assertThatThrownBy(() -> replayWithoutSession(service,
                    command(CoreMessageType.PROBE_INCREMENT, 3, 1001, CoreProtocol.probePayload(1))))
                    .isInstanceOf(org.agrona.concurrent.AgentTerminationException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(service.state().probeValue()).isZero();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void queriesAndFollowingCommandsObserveCompletedLogCallbacks() throws Exception {
        TradingOwnerTestSupport service = service();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            TradingCoreRuntime state = service.state();
            assertThat(CoreTestCompletion.applyAsynchronously(state, timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            var metrics = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LANE_METRICS_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 0, 1_000, 8), new byte[0]);
            onSessionMessage(service, responses, metrics);
            assertThat(responses).hasSize(1);
            CoreMessage later = command(CoreMessageType.PLACE_ORDER, 3, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(18_002, "1",
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "later")));
            onSessionMessage(service, responses, later);
            assertThat(state.matchingSequence(later.header().commandId())).isZero();
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(responses).hasSize(2);

            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 1_000, 9), new byte[0]);
            onSessionMessage(service, responses, query);
            onSessionMessage(service, responses, metrics);
            assertThat(responses).hasSize(4);
            assertThat(service.doBackgroundWork(0)).isZero();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void handsRuntimeOwnershipFromConstructionThreadToClusterServiceThread() throws Exception {
        TradingOwnerTestSupport service = service();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<CoreResponse> response = new AtomicReference<>();
        Thread serviceThread = new Thread(() -> {
            try {
                service.onStart(cluster(), null);
                response.set(CoreTestCompletion.applyAsynchronously(service.state(), command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                        CoreProtocol.probePayload(1))));
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                service.onTerminate(null);
            }
        }, "clustered-service-owner-test");

        serviceThread.start();
        serviceThread.join();

        assertThat(failure.get()).isNull();
        assertThat(response.get()).isNotNull();
        assertThat(response.get().status()).isEqualTo(ResponseStatus.APPLIED);
    }

    @Test
    void loggedTimerCompletesMatchingExactlyOnceWithoutBackgroundWork() {
        TradingOwnerTestSupport service = service();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(904, "1",
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                            false, "same-callback")));

            onSessionMessage(service, responses, place);
            service.onTimerEvent(service.state().appliedCommandCount(), 1_001);
            assertThat(service.doBackgroundWork(0)).isZero();

            assertThat(service.state().pendingMatchingCount()).isZero();
            assertThat(responses).hasSize(1);
            assertThat(CoreTestCompletion.orderState(service.state(), 1001, 904).status())
                    .isEqualTo("OPEN");
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void logCallbackCommitsMatchingBeforeTheFollowingCommand() throws Exception {
        TradingOwnerTestSupport service = service();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            TradingCoreRuntime state = service.state();
            assertThat(CoreTestCompletion.applyAsynchronously(state, timerInstrument()).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)),
                    UUID.fromString("00000000-0000-0000-0000-000000000012"))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(906, "1", CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "session-fence")),
                    UUID.fromString("00000000-0000-0000-0000-000000000013"));
            onSessionMessage(service, responses, place);
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(responses).hasSize(1);

            onSessionMessage(service, responses, command(CoreMessageType.PROBE_INCREMENT, 3, 1001,
                    CoreProtocol.probePayload(1),
                    UUID.fromString("00000000-0000-0000-0000-000000000014")));
            assertThat(service.doBackgroundWork(0)).isZero();

            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(CoreTestCompletion.orderState(state, 1001, 906).status())
                    .isEqualTo("OPEN");
            assertThat(responses).hasSize(2);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void loadsOneByteSnapshotFragmentsThroughBoundedSectionRecovery() {
        TradingOwnerTestSupport source = service();
        TradingOwnerTestSupport target = service();
        try {
            source.onStart(cluster(), null);
            assertThat(source.state().apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                    CoreProtocol.probePayload(9))).status()).isEqualTo(ResponseStatus.APPLIED);
            byte[] snapshot = source.state().snapshot(45);
            AtomicInteger offset = new AtomicInteger();
            UnsafeBuffer buffer = new UnsafeBuffer(snapshot);
            TradingOwnerTestSupport.SnapshotFragmentSource fragments = (handler, fragmentLimit) -> {
                if (offset.get() == snapshot.length) return 0;
                handler.onFragment(buffer, offset.getAndIncrement(), 1, null);
                return 1;
            };
            target.onStart(cluster(), null);

            target.loadSnapshot(fragments, () -> offset.get() == snapshot.length);

            assertThat(target.state().probeValue()).isEqualTo(9);
            assertThat(target.state().stateHash()).isEqualTo(source.state().stateHash());
            assertThat(offset).hasValue(snapshot.length);
        } finally {
            source.onTerminate(null);
            target.onTerminate(null);
        }
    }

    @Test
    void emptyAndIncompleteFragmentSourcesFailBeforeStateReplacement() {
        TradingOwnerTestSupport service = service();
        try {
            service.onStart(cluster(), null);
            TradingCoreRuntime before = service.state();
            assertThatThrownBy(() -> service.loadSnapshot((handler, limit) -> 0, () -> true))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
            assertThat(service.state()).isSameAs(before);

            byte[] snapshot = before.snapshot(46);
            byte[] truncated = Arrays.copyOf(snapshot, snapshot.length - 1);
            AtomicInteger delivered = new AtomicInteger();
            assertThatThrownBy(() -> service.loadSnapshot((handler, limit) -> {
                if (delivered.getAndIncrement() > 0) return 0;
                handler.onFragment(new UnsafeBuffer(truncated), 0, truncated.length, null);
                return 1;
            }, () -> delivered.get() > 0))
                    .isInstanceOf(ProtocolException.class);
            assertThat(service.state()).isSameAs(before);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void rejectsSnapshotFragmentsBeyondBoundedRecoveryBuffer() {
        TradingOwnerTestSupport.ensureSnapshotCapacity(SectionedCoreSnapshotCodec.MAX_SNAPSHOT_BYTES - 1, 1);

        assertThatThrownBy(() -> TradingOwnerTestSupport.ensureSnapshotCapacity(
                SectionedCoreSnapshotCodec.MAX_SNAPSHOT_BYTES - 1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Aeron core snapshot exceeds maximum size");
    }

    @Test
    void doesNotReplaceStateAfterCorruptSnapshot() {
        TradingOwnerTestSupport service = service();
        try {
            service.onStart(cluster(), null);
            TradingCoreRuntime before = service.state();
            byte[] snapshot = before.snapshot();
            snapshot[snapshot.length / 2] ^= 1;

            assertThatThrownBy(() -> service.restoreSnapshot(snapshot))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("checksum");
            assertThat(service.state()).isSameAs(before);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void pairedManifestMismatchFailsBeforeLiveStateReplacement() {
        TradingOwnerTestSupport service = service();
        try {
            service.onStart(cluster(), null);
            TradingCoreRuntime before = service.state();
            assertThat(before.apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                    CoreProtocol.probePayload(9))).status()).isEqualTo(ResponseStatus.APPLIED);
            long beforeHash = before.stateHash();
            long beforeSequence = before.appliedCommandCount();
            byte[] snapshot = before.snapshot(75);
            ByteBuffer manifest = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
            int snapshotIdOffset = SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                    + SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH + 53;
            manifest.putLong(snapshotIdOffset, manifest.getLong(snapshotIdOffset) + 1);
            CRC32C checksum = new CRC32C();
            checksum.update(snapshot, 0, snapshot.length - 16);
            manifest.putLong(snapshot.length - Long.BYTES, checksum.getValue());

            assertThatThrownBy(() -> service.restoreSnapshot(snapshot))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("snapshot id");
            assertThat(service.state()).isSameAs(before);
            assertThat(service.state().stateHash()).isEqualTo(beforeHash);
            assertThat(service.state().appliedCommandCount()).isEqualTo(beforeSequence);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void propagatesFatalMatcherDivergenceFromSnapshotCallback() {
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            TradingCoreRuntime state = service.state();
            long sequence = preparePendingPlace(state, 901);
            var matcherFailure = new com.surprising.aeron.service.matching.CoreMatchingResult(
                    false, "EXCHANGE_CORE_FAILURE");
            Throwable fatal = catchThrowable(() -> state.completeMatching(sequence, matcherFailure, 2_000, 3));

            assertThat(fatal).isInstanceOf(
                    FatalMatchingDivergenceException.class);
            assertThatThrownBy(() -> service.onTakeSnapshot(null)).isSameAs(fatal);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void loggedSessionOpenAndBackgroundNeverScheduleProgressTimers() {
        TradingOwnerTestSupport service = service();
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong correlationId = new AtomicLong();
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        try {
            service.onStart(clusterWithTimerBackpressure(attempts, correlationId), null);
            preparePendingPlace(service.state(), 902);
            service.onSessionOpen(clientSession(responses), 1_000);

            assertThat(attempts).hasValue(0);
            assertThat(correlationId).hasValue(0);
            service.doBackgroundWork(0);
            assertThat(attempts).hasValue(0);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void followerDoesNotSynthesizeHistoricalMatcherTimeoutDuringReplay() {
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(Cluster.Role.FOLLOWER), null);
        try {
            long sequence = preparePendingPlace(service.state(), 905);
            assertThat(CoreTestCompletion.awaitMatchingResult(service.state(), sequence,
                    TimeUnit.SECONDS.toNanos(5))).isNotNull();

            service.onTimerEvent(Long.MAX_VALUE - 1, 31_000);

            assertThat(service.state().pendingMatching(sequence)).isNotNull();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void snapshotWaitsForAsynchronousMatcherCaptureAndReleasesCommandAdmission() {
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            assertThat(service.captureSnapshot(7)).isNotEmpty();
            assertThat(service.state().apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                    CoreProtocol.probePayload(7))).status()).isEqualTo(ResponseStatus.APPLIED);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void snapshotCaptureTimeoutIsFailClosed() {
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            assertThatThrownBy(() -> service.captureSnapshot(9, System.nanoTime()))
                    .isInstanceOf(TradingCoreRuntime.SnapshotFenceTimeoutException.class)
                    .hasMessage("snapshot fence timed out");
            assertThat(service.captureSnapshot(10)).isNotEmpty();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void backgroundAndSnapshotCannotCommitWorkOutsideALogCallback() {
        // Given
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            long pendingSequence = preparePendingPlace(service.state(), 903);
            var matchingResult = awaitMatching(service.state(), pendingSequence);
            assertThat(matchingResult).isNotNull();
            if (matchingResult instanceof com.surprising.aeron.service.matching.CoreMatchingResult core) {
                service.state().matchingFlow.publishMatchingCompletion(pendingSequence, core);
            }

            // When
            assertThat(service.doBackgroundWork(0)).isZero();
            assertThatThrownBy(() -> service.captureSnapshot(8))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("unfinished business work outside cluster log callback");

            // Then
            assertThat(service.state().pendingMatchingCount()).isOne();
            assertThat(service.state().pendingMatching(pendingSequence)).isNotNull();
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void restoresSuccessfulSnapshotRoundTripWithoutTimingPoll() {
        // Given
        TradingOwnerTestSupport service = service();
        try {
            service.onStart(cluster(), null);
            TradingCoreRuntime before = service.state();
            assertThat(before.apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
                    CoreProtocol.probePayload(7))).status()).isEqualTo(ResponseStatus.APPLIED);
            byte[] snapshot = before.snapshot(11);

            // When
            service.restoreSnapshot(snapshot);

            // Then
            assertThat(service.state()).isNotSameAs(before);
            assertThat(service.state().probeValue()).isEqualTo(7);
            assertThat(service.state().appliedCommandCount()).isEqualTo(1);
        } finally {
            service.onTerminate(null);
        }
    }

    private static long preparePendingPlace(TradingCoreRuntime state, long orderId) {
        assertThat(CoreTestCompletion.applyAsynchronously(state, instrument()).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                .status()).isEqualTo(ResponseStatus.APPLIED);
        UUID commandId = UUID.randomUUID();
        CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1", CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "service-" + orderId)), commandId);
        CoreResponse response = state.apply(place);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // Finish admission only: these tests deliberately leave matching uncommitted.
        while (state.hasPendingDirectCommand()) {
            response = state.pollDirectCommand();
            state.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("place admission timeout");
            Thread.onSpinWait();
        }
        assertThat(response).isNotNull();
        assertThat(response.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        long sequence = state.matchingSequence(commandId);
        assertThat(sequence).isPositive();
        return sequence;
    }


    private static ClientSession clientSession(List<byte[]> responses) {
        return (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "id" -> 91L;
                    case "isClosing" -> false;
                    case "offer" -> {
                        if (arguments.length == 3) {
                            var buffer = (org.agrona.DirectBuffer) arguments[0];
                            int offset = (int) arguments[1];
                            int length = (int) arguments[2];
                            byte[] response = new byte[length];
                            buffer.getBytes(offset, response);
                            responses.add(response);
                        }
                        yield 1L;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static void onSessionMessage(
            TradingOwnerTestSupport service, List<byte[]> responses, CoreMessage request) {
        byte[] encoded = CoreMessageCodec.encode(request);
        service.onSessionMessage(clientSession(responses), 1_000, new UnsafeBuffer(encoded), 0,
                encoded.length, aeronHeader());
        finishCommands(service);
    }


    private static void finishCommands(TradingOwnerTestSupport service) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            service.pollCommands();
            if (service.pendingCommandCount() == 0) return;
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("asynchronous command completion timeout: " + service.stalledCommandDiagnostics());
    }

    private static Header aeronHeader() {
        return new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0)
                .initialTermId(0).positionBitsToShift(16);
    }

    private static CoreMessage timerInstrument() {
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand("1",
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), ProductLine.SPOT,
                CommandSource.OPERATIONS, 88, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeRegisterInstrument(instrument));
    }


    private static CoreMessage instrument() {
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand("1",
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 88, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeRegisterInstrument(instrument));
    }

    private static CoreMessage command(CoreMessageType type, long sequence, long userId, byte[] payload) {
        return command(type, sequence, userId, payload, UUID.randomUUID());
    }

    private static CoreMessage command(
            CoreMessageType type,
            long sequence,
            long userId,
            byte[] payload,
            UUID commandId) {
        return new CoreMessage(CoreMessageHeader.command(type, commandId, ProductLine.SPOT,
                CommandSource.GATEWAY, 77, sequence, userId, 1_000, sequence), payload);
    }

    private static Cluster cluster() {
        return cluster(Cluster.Role.LEADER);
    }

    private static TradingOwnerTestSupport service() {
        return new TradingOwnerTestSupport(ProductLine.SPOT);
    }

    private static Cluster cluster(Cluster.Role role) {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "role" -> role;
                    case "logPosition" -> 7L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> 1_000L;
                    case "scheduleTimer" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static com.surprising.aeron.service.matching.MatchingResult awaitMatching(
            TradingCoreRuntime state, long sequence) {
        com.surprising.aeron.service.matching.MatchingResult result = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (result == null && System.nanoTime() < deadline) {
            result = state.takeMatchingResult(sequence);
            if (result == null) Thread.onSpinWait();
        }
        return result;
    }

    private static Cluster clusterWithTimerBackpressure(AtomicInteger attempts, AtomicLong correlationId) {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "logPosition" -> 7L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> 1_000L;
                    case "scheduleTimer" -> {
                        correlationId.set((long) arguments[0]);
                        yield attempts.incrementAndGet() >= 3;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
