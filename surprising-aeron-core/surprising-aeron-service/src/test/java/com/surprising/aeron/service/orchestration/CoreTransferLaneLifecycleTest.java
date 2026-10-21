package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CompleteTransferCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CorePendingTransferCodec;
import com.surprising.aeron.protocol.CorePendingTransferView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.orchestration.pipeline.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/** Pending identity and money are Lane-owned; no snapshot/recovery is used. */
@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class CoreTransferLaneLifecycleTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void laneOwnsIdentityDebitCompletionAndPagedQueriesAcrossProducts(ProductLine product) throws Exception {
        ProductLine destination = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (var source = new TradingCoreRuntime(product); var target = new TradingCoreRuntime(destination)) {
            long first = 7, second = 8;
            while (source.runtimeState.topology().accountLaneId(second) == source.runtimeState.topology().accountLaneId(first)) second++;
            deposit(source, first, 100); deposit(source, second, 200);
            long handoff = source.runtimeState.laneHandoffEpoch();
            var a = transfer(product, destination, 91, first, 30);
            var b = transfer(product, destination, 92, second, 10);
            try (var guard = new OwnerAccountReadGuard(source.runtimeState)) {
                assertThatThrownBy(() -> source.runtimeState.pendingTransfer(91))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Account Lane");
                var out = command(source, CoreMessageType.TRANSFER_OUT, first, TradingCommandCodec.encodeTransferFunds(a));
                assertThat(apply(source, out).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(apply(source, out).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, first,
                        TradingCommandCodec.encodeTransferFunds(a))).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, second,
                        TradingCommandCodec.encodeTransferFunds(b))).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(query(source, 256)).extracting(v -> v.command().transferId()).containsExactly(91L, 92L);
                assertThat(query(source, 1)).extracting(v -> v.command().transferId()).containsExactly(91L);
                assertThat(query(source, 1)).extracting(v -> v.command().transferId()).containsExactly(92L);
                assertThat(query(source, 1)).extracting(v -> v.command().transferId()).containsExactly(91L);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, first,
                        TradingCommandCodec.encodeTransferFunds(transfer(product, destination, 91, first, 31)))).resultCode())
                        .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, second,
                        TradingCommandCodec.encodeTransferFunds(transfer(product, destination, 91, second, 30)))).resultCode())
                        .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                assertThat(apply(source, command(source, CoreMessageType.TRANSFER_OUT, first,
                        TradingCommandCodec.encodeTransferFunds(transfer(product, destination, 93, first, 71)))).resultCode())
                        .isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
                assertThat(source.runtimeState.pendingTransferUser(93)).isZero();
                for (var transfer : List.of(a, b)) {
                    var credit = command(target, CoreMessageType.TRANSFER_IN, 22, TradingCommandCodec.encodeTransferFunds(transfer));
                    assertThat(apply(target, credit).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(apply(target, credit).status()).isEqualTo(ResponseStatus.DUPLICATE);
                }
                assertThat(apply(source, command(source, CoreMessageType.COMPLETE_TRANSFER, second,
                        TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(91)))).resultCode())
                        .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                for (var transfer : List.of(a, b)) {
                    assertThat(apply(source, command(source, CoreMessageType.COMPLETE_TRANSFER, transfer.sourceUserId(),
                            TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(transfer.transferId()))))
                            .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    long revision = source.runtimeState.revision();
                    assertThat(apply(source, command(source, CoreMessageType.COMPLETE_TRANSFER, transfer.sourceUserId(),
                            TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(transfer.transferId()))))
                            .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(source.runtimeState.revision()).isEqualTo(revision);
                }
                assertThat(query(source, 256)).isEmpty();
                assertThat(source.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            }
            var firstUser = CoreTestCompletion.userState(source, first);
            var secondUser = CoreTestCompletion.userState(source, second);
            var recipient = CoreTestCompletion.userState(target, 22);
            assertThat(firstUser.balances()).singleElement().satisfies(v -> {
                assertThat(v.availableUnits()).isEqualTo(70); assertThat(v.lockedUnits()).isZero();
            });
            assertThat(secondUser.balances()).singleElement().satisfies(v -> assertThat(v.availableUnits()).isEqualTo(190));
            assertThat(recipient.balances()).singleElement().satisfies(v -> assertThat(v.availableUnits()).isEqualTo(40));
            assertThat(firstUser.reservations()).isEmpty(); assertThat(secondUser.positions()).isEmpty();
            source.assertClusterCallbackComplete(); target.assertClusterCallbackComplete();
        }
    }

    private TransferFundsCommand transfer(ProductLine source, ProductLine target, long id, long user, long amount) {
        return new TransferFundsCommand(id, source, target, source.accountTypeCode(), target.accountTypeCode(),
                "USDT", amount, "lane-transfer-" + id, "", user, 22);
    }

    private void deposit(TradingCoreRuntime core, long user, long amount) {
        assertThat(apply(core, command(core, CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", amount))))
                .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }

    private CoreResponse apply(TradingCoreRuntime core, CoreMessage message) {
        return CoreTestCompletion.applyAsynchronously(core, message);
    }

    private CoreMessage command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        long id = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(941, id), core.productLine,
                CommandSource.OPERATIONS, 941, id, user, TIME, id), payload);
    }

    private List<CorePendingTransferView> query(TradingCoreRuntime core, int limit) {
        long id = ++sequence;
        var request = new CoreMessage(CoreMessageHeader.query(CoreMessageType.PENDING_TRANSFER_QUERY,
                new UUID(942, id), core.productLine, CommandSource.OPERATIONS, 942, id, 0, TIME, id),
                CorePendingTransferCodec.encodeQuery(limit));
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        var response = core.apply(request);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
            long queryId = core.bookQueries.querySequence(request.header().commandId());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((response = core.bookQueries.takeQueryResult(queryId)) == null) {
                core.assertHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("pending transfer query timed out");
                Thread.onSpinWait();
            }
        }
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CorePendingTransferCodec.decode(response.data());
    }
}
