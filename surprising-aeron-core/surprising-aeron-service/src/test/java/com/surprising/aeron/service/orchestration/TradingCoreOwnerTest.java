package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.matcher.MatcherPipelineGroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
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
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
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
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void terminalPreparationFailureRollsBackOnLaneAndRetainsRejection(boolean capacityFailure) throws Exception {
        var service = service();
        service.onStart(cluster(), null);
        var owner = service.state();
        var runtime = owner.runtimeState;
        try {
            owner.apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10000))));
            int assetId = owner.identities.assetId("USDT");
            var epoch = runtime.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long before = epoch.getLong(runtime);
            var request = command(CoreMessageType.ADJUST_BALANCE, 2, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            CoreResponse result;
            runtime.enterAsynchronousCommandScope();
            try {
                assertThat(owner.applyDecodedCommand(request, 2000, 2000, null, false)).isNull();
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
            assertThat(runtime.balance(1001, assetId).availableUnits()).isEqualTo(10000);
            assertThat(runtime.balance(1001, assetId).lockedUnits()).isZero();
            assertThat(epoch.getLong(runtime)).isEqualTo(before);
            assertThat(owner.admissions.queuedMatching).isEmpty();
            var duplicate = owner.apply(request);
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
                var algo = new com.surprising.aeron.protocol.CoreAlgoOrderView(501, 1001, "algo-client", "BTC-USDT", 0,
                        CoreOrderSide.BUY, 0, 100, 10, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                        false, false, CoreTimeInForce.IOC, 0, 0, "", "trace",
                        1, 1, 0, 1, revision, revision, List.of(), 0, 0, 0);
                onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER,
                        revision, 1001, com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(algo)));
                finishCommands(service);
                assertThat(runtime.algoOrder(501).revision()).isEqualTo(revision);
                assertThat(runtime.algoOrder(501).userId()).isEqualTo(1001);
            }
            assertThat(epoch.getLong(runtime)).isEqualTo(before);
            long revisionBeforeFailure = runtime.revision();
            runtime.setMetadata(ProductLine.SPOT, Long.MAX_VALUE);
            var next = new com.surprising.aeron.protocol.CoreAlgoOrderView(501, 1001, "algo-client", "BTC-USDT", 0,
                    CoreOrderSide.BUY, 0, 100, 10, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                    false, false, CoreTimeInForce.IOC, 0, 0, "", "trace",
                    1, 1, 0, 1, 4, 4, List.of(), 0, 0, 0);
            byte[] payload = com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(next);
            onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER, 4, 1001, payload));
            finishCommands(service);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getLast()).payloadUnsafe()).status())
                    .isEqualTo(ResponseStatus.REJECTED);
            assertThat(runtime.algoOrder(501).revision()).isEqualTo(3);
            assertThat(runtime.revision()).isEqualTo(Long.MAX_VALUE);
            runtime.setMetadata(ProductLine.SPOT, revisionBeforeFailure);
            onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER, 5, 1001, payload));
            finishCommands(service);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getLast()).payloadUnsafe()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(runtime.algoOrder(501).revision()).isEqualTo(4);
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
                    com.surprising.aeron.protocol.CoreCancelAllAfterAction.SET, 1001, "BTC-USDT",
                    1000, 2000, 0, 0, 0, 1000);
            onSessionMessage(service, responses, command(CoreMessageType.UPDATE_CANCEL_ALL_AFTER, 3, 1001,
                    com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeCommand(timer)));
            finishCommands(service);
            assertThat(epoch.getLong(runtime)).isEqualTo(before);
            assertThat(service.state().probeValue()).isEqualTo(3);
            assertThat(runtime.cancelAllAfterTimer(new com.surprising.aeron.service.state.model.CoreCancelAllAfterKey(
                    1001, "BTC-USDT")).revision()).isEqualTo(1);
            assertThat(service.state().appliedCommandCount()).isEqualTo(3);
        } finally {
            service.onTerminate(null);
        }
    }

    @Test
    void bookQueryDoesNotWaitForBlockedMatcherAndCompletesOnLaterLoggedCallback() throws Exception {
        var service = service();
        var responses = new CopyOnWriteArrayList<byte[]>();
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            service.state().apply(timerInstrument());
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
                            new com.surprising.aeron.protocol.CoreOrderBookQuery("BTC-USDT", 10)));
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
            state.apply(timerInstrument());
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
            service.state().apply(timerInstrument());
            service.state().apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
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
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(904, "BTC-USDT",
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
            drainRealtime(outbox);
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
            assertThat(drainRealtime(outbox)).anySatisfy(frame -> {
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
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(20000,"BTC-USDT",
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
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(20_000,"BTC-USDT",
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
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT",
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
                            new com.surprising.aeron.protocol.CoreOrderBookQuery("BTC-USDT", 10)));
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
            assertThat(state.apply(timerInstrument()).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            var metrics = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LANE_METRICS_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 0, 1_000, 8), new byte[0]);
            onSessionMessage(service, responses, metrics);
            assertThat(responses).hasSize(1);
            CoreMessage later = command(CoreMessageType.PLACE_ORDER, 3, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(18_002, "BTC-USDT",
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
                response.set(service.state().apply(command(CoreMessageType.PROBE_INCREMENT, 1, 1001,
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
            assertThat(service.state().apply(timerInstrument()).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(service.state().apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(904, "BTC-USDT",
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                            false, "same-callback")));

            onSessionMessage(service, responses, place);
            service.onTimerEvent(service.state().appliedCommandCount(), 1_001);
            assertThat(service.doBackgroundWork(0)).isZero();

            assertThat(service.state().pendingMatchingCount()).isZero();
            assertThat(responses).hasSize(1);
            assertThat(service.state().tradingState().order(904).status())
                    .isEqualTo(com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN);
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
            assertThat(state.apply(timerInstrument()).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)),
                    UUID.fromString("00000000-0000-0000-0000-000000000012"))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(906, "BTC-USDT", CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "session-fence")),
                    UUID.fromString("00000000-0000-0000-0000-000000000013"));
            onSessionMessage(service, responses, place);
            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(responses).hasSize(1);

            onSessionMessage(service, responses, command(CoreMessageType.PROBE_INCREMENT, 3, 1001,
                    CoreProtocol.probePayload(1),
                    UUID.fromString("00000000-0000-0000-0000-000000000014")));
            assertThat(service.doBackgroundWork(0)).isZero();

            assertThat(state.pendingMatchingCount()).isZero();
            assertThat(state.tradingState().order(906).status())
                    .isEqualTo(com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN);
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
            assertThat(awaitMatching(service.state(), sequence)).isNotNull();

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
            service.state().matchingFlow.publishMatchingCompletion(pendingSequence, matchingResult);

            // When
            assertThat(service.doBackgroundWork(0)).isZero();
            assertThatThrownBy(() -> service.captureSnapshot(8))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unfinished business work outside cluster log callback");

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
        assertThat(state.apply(instrument()).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                .status()).isEqualTo(ResponseStatus.APPLIED);
        UUID commandId = UUID.randomUUID();
        CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "service-" + orderId)), commandId);
        assertThat(state.apply(place).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        return state.matchingSequence(commandId);
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
        throw new AssertionError("asynchronous command completion timeout");
    }

    private static Header aeronHeader() {
        return new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0)
                .initialTermId(0).positionBitsToShift(16);
    }

    private static CoreMessage timerInstrument() {
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand("BTC-USDT",
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), ProductLine.SPOT,
                CommandSource.OPERATIONS, 88, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeRegisterInstrument(instrument));
    }


    private static CoreMessage instrument() {
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand("BTC-USDT",
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

    private static com.surprising.aeron.service.matching.CoreMatchingResult awaitMatching(
            TradingCoreRuntime state, long sequence) {
        com.surprising.aeron.service.matching.CoreMatchingResult result = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (result == null && System.nanoTime() < deadline) {
            var matching = state.takeMatchingResult(sequence);
            if (matching instanceof com.surprising.aeron.service.matching.CoreMatchingResult core) result = core;
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
