package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreExportCodecTest {

    @Test
    void roundTripsEventBatchAckAndStatus() {
        UUID commandId = UUID.randomUUID();
        CoreUserStateView user = new CoreUserStateView(ProductLine.SPOT, 17, 2,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 900, 100)), List.of(), List.of());
        CoreOrderStateView order = new CoreOrderStateView(71, ProductLine.SPOT, 17, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, "OPEN", 1);
        CoreExecutionView execution = new CoreExecutionView(71, 72, 17, 18, 60_000, 1);
        CoreFundingPaymentView funding = new CoreFundingPaymentView(8, 17, "BTC-USDT",
                CoreMarginMode.CROSS, CorePositionSide.NET, "USDT", 2, 120_000, 100, -12);
        CoreLiquidationView liquidation = new CoreLiquidationView(3, 17, "BTC-USDT", "USDT",
                CoreMarginMode.ISOLATED, CorePositionSide.NET, 3, 8, 2, 2, 12,
                59_000, 25_000, 3, "INSURANCE_REQUIRED");
        CoreTreasuryAssetView treasury = new CoreTreasuryAssetView("USDT", 4, 9, 12);
        CoreExportEvent event = new CoreExportEvent(7, 11, 13, commandId,
                CoreMessageType.ADJUST_BALANCE, ResponseStatus.APPLIED, CoreResultCode.NONE,
                17, new byte[]{1, 2, 3}, List.of(user), List.of(order), List.of(execution), List.of(funding),
                List.of(liquidation), List.of(treasury));
        CoreMessage message = new CoreMessage(new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION,
                WireMessageKind.EXPORT_EVENT, CoreMessageType.CORE_EVENT, commandId, ProductLine.SPOT,
                CoreRoute.DEFAULT, CommandSource.GATEWAY, 1, 7, 17, 19, 23), CoreExportCodec.encodeEvent(event));

        CoreExportEvent restored = CoreExportCodec.decodeEvent(message.payload());
        List<CoreMessage> batch = CoreExportCodec.decodeBatch(CoreExportCodec.encodeBatch(List.of(message)));
        CoreExportBatch batchWithStatus = CoreExportCodec.decodeBatchResponse(
                CoreExportCodec.encodeBatchWithStatus(6, List.of(message)));

        assertThat(restored.exportSequence()).isEqualTo(7);
        assertThat(restored.commandPayload()).containsExactly(1, 2, 3);
        assertThat(restored.changedUsers()).containsExactly(user);
        assertThat(restored.changedOrders()).containsExactly(order);
        assertThat(restored.executions()).containsExactly(execution);
        assertThat(restored.fundingPayments()).containsExactly(funding);
        assertThat(restored.changedLiquidations()).containsExactly(liquidation);
        assertThat(restored.changedTreasuryAssets()).containsExactly(treasury);
        assertThat(batch).containsExactly(message);
        assertThat(batch.getFirst().header().route()).isEqualTo(CoreRoute.DEFAULT);
        assertThat(batchWithStatus.acknowledgedSequence()).isEqualTo(6);
        assertThat(batchWithStatus.events()).containsExactly(message);
        assertThat(CoreExportCodec.decodeAck(CoreExportCodec.encodeAck(new AckExportCommand(7))))
                .isEqualTo(new AckExportCommand(7));
        CoreExportStatus status = new CoreExportStatus(6, 8, 1, 256, 1_000_000, 64L * 1024 * 1024);
        assertThat(CoreExportCodec.decodeStatus(CoreExportCodec.encodeStatus(status))).isEqualTo(status);
    }

    @Test
    void rejectsTruncatedOrTrailingPayloads() {
        assertThatThrownBy(() -> CoreExportCodec.decodeBatch(new byte[]{1, 0, 0, 0}))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeAck(new byte[7]))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchQuery(new byte[]{0, 0, 0, 0}))
                .isInstanceOf(ProtocolException.class);
    }
}
