package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommittedPortfolioFlowTest {
    private static final long NOW = 1_792_454_400_000L;
    private long sequence;

    @Test void actualAdjustmentsExcludeRetriesConflictsZeroAndRejections() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            byte[] first = adjust(replay, 100);
            assertEquals("100", replay.balanceFlow().amountUnits());
            assertNull(replay.balanceFlow().transfer());
            replay.apply(first, NOW + ++sequence, sequence);
            assertNull(replay.balanceFlow());
            var original = CoreMessageCodec.decode(first);
            var conflict = new CoreMessage(original.header(), TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 200)));
            replay.apply(CoreMessageCodec.encode(conflict), NOW + ++sequence, sequence);
            assertEquals(CoreResultCode.IDEMPOTENCY_CONFLICT, replay.commandResult().code());
            assertNull(replay.balanceFlow());
            adjust(replay, 0);
            assertNull(replay.balanceFlow());
            adjust(replay, -101);
            assertEquals(ResponseStatus.REJECTED, replay.commandResult().status());
            assertNull(replay.balanceFlow());
            adjust(replay, -10);
            assertEquals("-10", replay.balanceFlow().amountUnits());
            assertEquals("90", replay.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).netAssetUnits().get("USDT"));
        }
    }

    @Test void crossProductMoveCapturesImmediateDebitAndCreditAndPreservesTransferIdentity() {
        try (var source = new CommittedTradeReplay(ProductLine.SPOT, null);
             var target = new CommittedTradeReplay(ProductLine.LINEAR_PERPETUAL, null)) {
            adjust(source, 1000);
            var transfer = new TransferFundsCommand(91, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                    "FUNDING", "USDT_PERPETUAL", "USDT", 100, "synthetic-91", "", 7, 7);
            send(source, ProductLine.SPOT, 7, CoreMessageType.TRANSFER_OUT, TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals("-100", source.balanceFlow().amountUnits());
            assertEquals(transfer, source.balanceFlow().transfer());
            assertFalse(source.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).complete());
            byte[] incoming = send(target, ProductLine.LINEAR_PERPETUAL, 7, CoreMessageType.TRANSFER_IN,
                    TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals("100", target.balanceFlow().amountUnits());
            assertEquals(transfer, target.balanceFlow().transfer());
            target.apply(incoming, NOW + ++sequence, sequence);
            assertNull(target.balanceFlow());
            send(source, ProductLine.SPOT, 7, CoreMessageType.COMPLETE_TRANSFER,
                    TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(91)));
            assertNull(source.balanceFlow());
            assertTrue(source.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).complete());
            assertEquals("900", source.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).netAssetUnits().get("USDT"));
            assertEquals("100", target.portfolioSnapshot(NOW + sequence, 60_000).users().get(7L).netAssetUnits().get("USDT"));
        }
    }

    @Test void crossUserReceiptsKeepBothUsersSoTheyCannotBecomeTradingProfit() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            adjust(replay, 1000);
            var transfer = new TransferFundsCommand(92, ProductLine.SPOT, ProductLine.SPOT,
                    "SPOT", "SPOT", "USDT", 100, "synthetic-92", "", 7, 8);
            send(replay, ProductLine.SPOT, 7, CoreMessageType.TRANSFER_OUT, TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals("-100", replay.balanceFlow().amountUnits());
            assertEquals(7, replay.balanceFlow().userId());
            assertEquals(8, replay.balanceFlow().transfer().targetUserId());
            send(replay, ProductLine.SPOT, 8, CoreMessageType.TRANSFER_IN, TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals(8, replay.balanceFlow().userId());
            assertEquals(7, replay.balanceFlow().transfer().sourceUserId());
            assertEquals("100", replay.balanceFlow().amountUnits());
            send(replay, ProductLine.SPOT, 7, CoreMessageType.COMPLETE_TRANSFER,
                    TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(92)));
            assertNull(replay.balanceFlow());
        }
    }

    @Test void malformedOrWrongRecipientCommandsStillReachTheAuthoritativeRejection() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            send(replay, ProductLine.SPOT, 7, CoreMessageType.ADJUST_BALANCE, new byte[]{1});
            assertEquals(ResponseStatus.REJECTED, replay.commandResult().status());
            assertNull(replay.balanceFlow());
            var transfer = new TransferFundsCommand(93, ProductLine.SPOT, ProductLine.SPOT,
                    "SPOT", "SPOT", "USDT", 100, "synthetic-93", "", 7, 8);
            send(replay, ProductLine.SPOT, 9, CoreMessageType.TRANSFER_IN, TradingCommandCodec.encodeTransferFunds(transfer));
            assertEquals(ResponseStatus.REJECTED, replay.commandResult().status());
            assertNull(replay.balanceFlow());
        }
    }

    private byte[] adjust(CommittedTradeReplay replay, long amount) {
        return send(replay, ProductLine.SPOT, 7, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", amount)));
    }

    private byte[] send(CommittedTradeReplay replay, ProductLine product, long user, CoreMessageType type, byte[] payload) {
        long id = ++sequence;
        var command = new CoreMessage(CoreMessageHeader.command(type, new UUID(88, id), product,
                CommandSource.OPERATIONS, 88, id, user, NOW + id, id), payload);
        byte[] bytes = CoreMessageCodec.encode(command);
        replay.apply(bytes, NOW + id, id);
        return bytes;
    }
}
