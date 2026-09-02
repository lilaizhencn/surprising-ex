package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CoreExportCodecTest {

    @Test
    void encodesUnsignedEventWithoutIntegrityTrailer() {
        CoreExportEvent event = event(ProductLine.SPOT, 1, UUID.randomUUID(), List.of(), List.of(),
                CoreExportEvent.Tombstones.empty());

        byte[] encoded = CoreExportCodec.encodeEvent(event);

        assertThat(CoreExportCodec.encodedEventLength(event)).isEqualTo(encoded.length);
        assertThat(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt())
                .isEqualTo(0xC0E7_000A);
        assertThat(encoded).hasSize(324);
    }

    @Test
    void rejectsLegacySignedEventVersion() {
        CoreExportEvent event = event(ProductLine.SPOT, 1, UUID.randomUUID(), List.of(), List.of(),
                CoreExportEvent.Tombstones.empty());
        byte[] legacy = CoreExportCodec.encodeEvent(event);
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putInt(0xC0E7_0009);

        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(legacy))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("unsupported export event version");
    }

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
        byte[] fingerprintBytes = new byte[CommandFingerprint.LENGTH];
        for (int index = 0; index < fingerprintBytes.length; index++) fingerprintBytes[index] = (byte) index;
        CommandFingerprint fingerprint = CommandFingerprint.fromBytes(fingerprintBytes);
        var evidence = List.of(new CoreExportEvent.MatcherEvidence(43, 2, 71, 72, 1, 60_000));
        var terminalIds = new CoreExportEvent.TerminalIds(List.of(71L), List.of(3L), List.of(9L));
        var fundingProgress = new CoreFundingProgressView(8, false, 18, 2);
        var settlementProgress = new CoreSettlementProgressView(9, false, false, 72, 0, 1, 0);
        var postings = List.of(new CoreFundsPostingView("USDT", CoreFundsPostingView.OwnerKind.USER, 17,
                CoreFundsPostingView.Subledger.AVAILABLE, 12));
        var tombstones = new CoreExportEvent.Tombstones(List.of(99L),
                List.of(new CoreExportEvent.UserAssetKey(17, "BTC")),
                List.of(new CoreExportEvent.UserOrderKey(17, 70)), List.of(70L),
                List.of(new CoreExportEvent.UserPositionKey(17, "ETH-USDT", CorePositionSide.NET)),
                List.of(new CoreExportEvent.UserLeverageKey(17, "ETH-USDT", CoreMarginMode.CROSS)),
                List.of(2L), List.of(5L), List.of(8L), List.of("BTC"));
        CoreExportEvent event = new CoreExportEvent(7, 11, 13, commandId,
                CoreMessageType.ADJUST_BALANCE, ResponseStatus.APPLIED, CoreResultCode.NONE,
                17, new byte[]{1, 2, 3}, List.of(user), List.of(order), List.of(execution), List.of(funding),
                List.of(liquidation), List.of(treasury), List.of(), 10, 20, 21,
                CoreRoute.DEFAULT.version(), 31, 32, 11,
                new CoreMatcherTransition(40, 42, 0x1020_3040_5060_7080L, 0x1121_3141_5161_7181L),
                23, postings, fingerprint, evidence, terminalIds, 10, 11, 20, 21,
                fundingProgress, settlementProgress, tombstones);
        CoreMessage message = new CoreMessage(new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION,
                WireMessageKind.EXPORT_EVENT, CoreMessageType.CORE_EVENT, commandId, ProductLine.SPOT,
                CoreRoute.DEFAULT, CommandSource.GATEWAY, 1, 7, 17, 19, 23), CoreExportCodec.encodeEvent(event));

        assertThat(CoreExportCodec.encodedEventLength(event)).isEqualTo(message.payloadLength());
        CoreExportEvent restored = CoreExportCodec.decodeEvent(message.payload());
        List<CoreMessage> batch = CoreExportCodec.decodeBatch(CoreExportCodec.encodeBatch(List.of(message)));
        CoreExportStatus status = new CoreExportStatus(6, 8, 1, 256, 1_000_000, 64L * 1024 * 1024);
        CoreExportBatch batchWithStatus = CoreExportCodec.decodeBatchResponse(
                CoreExportCodec.encodeBatchWithStatus(status, List.of(message)));

        assertThat(restored.exportSequence()).isEqualTo(7);
        assertThat(restored.commandId()).isEqualTo(commandId);
        assertThat(restored.commandType()).isEqualTo(CoreMessageType.ADJUST_BALANCE);
        assertThat(restored.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(restored.resultCode()).isEqualTo(CoreResultCode.NONE);
        assertThat(restored.userId()).isEqualTo(17);
        assertThat(restored.commandPayload()).containsExactly(1, 2, 3);
        assertThat(restored.changedUsers()).containsExactly(user);
        assertThat(restored.changedOrders()).containsExactly(order);
        assertThat(restored.executions()).containsExactly(execution);
        assertThat(restored.fundingPayments()).containsExactly(funding);
        assertThat(restored.changedLiquidations()).containsExactly(liquidation);
        assertThat(restored.changedTreasuryAssets()).containsExactly(treasury);
        assertThat(restored.routeVersion()).isEqualTo(CoreRoute.DEFAULT.version());
        assertThat(restored.topologyHash()).isEqualTo(31);
        assertThat(restored.laneRevisionHash()).isEqualTo(32);
        assertThat(restored.committedCoreSequence()).isEqualTo(11);
        assertThat(restored.matcherTransition()).isEqualTo(event.matcherTransition());
        assertThat(restored.beforeBusinessStateHash()).isEqualTo(10);
        assertThat(restored.businessStateHash()).isEqualTo(13);
        assertThat(restored.beforeFundsStateHash()).isEqualTo(20);
        assertThat(restored.fundsStateHash()).isEqualTo(21);
        assertThat(restored.clusterPosition()).isEqualTo(23);
        assertThat(restored.fundsPostings()).isEqualTo(postings);
        assertThat(restored.commandFingerprint()).isEqualTo(fingerprint);
        assertThat(restored.matcherEvidence()).isEqualTo(evidence);
        assertThat(restored.terminalIds()).isEqualTo(terminalIds);
        assertThat(restored.previousCoreSequence()).isEqualTo(10);
        assertThat(restored.coreSequence()).isEqualTo(11);
        assertThat(restored.previousProjectionSequence()).isEqualTo(20);
        assertThat(restored.projectionSequence()).isEqualTo(21);
        assertThat(restored.fundingProgress()).isEqualTo(fundingProgress);
        assertThat(restored.settlementProgress()).isEqualTo(settlementProgress);
        assertThat(restored.tombstones()).isEqualTo(tombstones);
        assertThat(CoreExportCodec.encodeEvent(restored)).containsExactly(message.payloadUnsafe());
        assertThat(batch).containsExactly(message);
        assertThat(batch.getFirst().header().route()).isEqualTo(CoreRoute.DEFAULT);
        assertThat(batchWithStatus.acknowledgedSequence()).isEqualTo(6);
        assertThat(batchWithStatus.events()).containsExactly(message);
        assertThat(CoreExportCodec.decodeAck(CoreExportCodec.encodeAck(new AckExportCommand(7))))
                .isEqualTo(new AckExportCommand(7));
        assertThat(CoreExportCodec.decodeStatus(CoreExportCodec.encodeStatus(status))).isEqualTo(status);
        assertThat(batchWithStatus.status()).isEqualTo(status);
    }

    @Test
    void rejectsTruncatedOrTrailingPayloads() {
        CoreExportEvent event = event(ProductLine.SPOT, 1, UUID.randomUUID(), List.of(), List.of(),
                CoreExportEvent.Tombstones.empty());
        byte[] encodedEvent = CoreExportCodec.encodeEvent(event);
        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(Arrays.copyOf(encodedEvent, encodedEvent.length - 1)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(Arrays.copyOf(encodedEvent, encodedEvent.length + 1)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatch(new byte[]{1, 0, 0, 0}))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeAck(new byte[7]))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchQuery(new byte[]{0, 0, 0, 0}))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void batchResponseCarriesCompletePostQueryStatus() {
        CoreExportStatus expected = new CoreExportStatus(12, 17, 4, 1_024,
                1_000_000, 64L * 1024 * 1024);

        CoreExportBatch actual = CoreExportCodec.decodeBatchResponse(
                CoreExportCodec.encodeBatchWithStatus(expected, List.of()));

        assertThat(actual.status()).isEqualTo(expected);
        assertThat(actual.events()).isEmpty();
    }

    @Test
    void rejectsMalformedStatusBearingBatchResponses() {
        CoreExportStatus status = new CoreExportStatus(0, 1, 0, 0, 1_000_000, 64L * 1024 * 1024);
        byte[] encoded = CoreExportCodec.encodeBatchWithStatus(status, List.of());

        assertThatThrownBy(() -> CoreExportCodec.decodeBatchResponse(
                Arrays.copyOf(encoded, CoreExportCodec.BATCH_STATUS_FIXED_LENGTH)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchResponse(
                Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(ProtocolException.class);

        byte[] invalidStatus = encoded.clone();
        ByteBuffer.wrap(invalidStatus).order(ByteOrder.LITTLE_ENDIAN).putInt(20, -1);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchResponse(invalidStatus))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void enforcesCompleteStatusBearingResponseMaximumAtBoundaryAndPlusOne() {
        CoreExportStatus status = new CoreExportStatus(0, 2, 1, 64, 1_000_000, 64L * 1024 * 1024);
        int maximumInnerBatchLength = CoreExportCodec.MAX_BATCH_ENCODED_LENGTH
                - CoreExportCodec.BATCH_STATUS_FIXED_LENGTH;
        CoreMessage maximumMessage = messageForBatchLength(maximumInnerBatchLength);
        byte[] exact = CoreExportCodec.encodeBatchWithStatus(status, List.of(maximumMessage));

        assertThat(exact).hasSize(CoreExportCodec.MAX_BATCH_ENCODED_LENGTH);
        assertThat(CoreExportCodec.decodeBatchResponse(exact).events()).containsExactly(maximumMessage);

        byte[] oversizedInnerBatch = CoreExportCodec.encodeBatch(
                List.of(messageForBatchLength(maximumInnerBatchLength + 1)));
        byte[] oversized = withStatusHeader(status, oversizedInnerBatch);
        assertThat(oversized).hasSize(CoreExportCodec.MAX_BATCH_ENCODED_LENGTH + 1);
        assertThatThrownBy(() -> CoreExportCodec.decodeBatchResponse(oversized))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void completeExportResponseFitsTheClusterPublicationMaximum() {
        int encodedCoreMessageLength = CoreProtocol.HEADER_LENGTH
                + CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH
                + CoreExportCodec.MAX_BATCH_ENCODED_LENGTH;

        assertThat(encodedCoreMessageLength).isLessThanOrEqualTo(CoreProtocol.CLUSTER_MAX_MESSAGE_LENGTH);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void roundTripsCompleteV10ContractForEveryProductLine(ProductLine productLine) {
        UUID commandId = UUID.randomUUID();
        CoreUserStateView user = new CoreUserStateView(productLine, 17, 3, CorePositionMode.ONE_WAY,
                List.of(new CoreBalanceView("USDT", 900, 100)), List.of(), List.of());
        CoreOrderStateView recreated = new CoreOrderStateView(71, productLine, 17, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, "OPEN", 2);
        CoreExportEvent.Tombstones tombstones = new CoreExportEvent.Tombstones(List.of(99L),
                List.of(new CoreExportEvent.UserAssetKey(99, "USDT")),
                List.of(new CoreExportEvent.UserOrderKey(99, 70)), List.of(70L),
                List.of(new CoreExportEvent.UserPositionKey(99, "BTC-USDT", CorePositionSide.NET)),
                List.of(new CoreExportEvent.UserLeverageKey(99, "BTC-USDT", CoreMarginMode.CROSS)),
                List.of(81L), List.of(91L), List.of(101L), List.of("USDT"));
        CoreMessage command = command(productLine, 7, commandId, 17, new byte[] {7});
        CoreExportEvent event = new CoreExportEvent(7, 7, 13, commandId, CoreMessageType.PROBE_INCREMENT,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 17, command.payloadUnsafe(), List.of(user),
                List.of(recreated), List.of(), List.of(), List.of(), List.of(), List.of(), 12, 20, 21,
                CoreRoute.DEFAULT.version(), 31, 32, 7, CoreMatcherTransition.unchanged(0, 0), 23,
                List.of(new CoreFundsPostingView("USDT", CoreFundsPostingView.OwnerKind.USER, 17,
                        CoreFundsPostingView.Subledger.AVAILABLE, 12)),
                CommandFingerprint.of(command), List.of(new CoreExportEvent.MatcherEvidence(43, 2, 71, 72, 1, 60_000)),
                new CoreExportEvent.TerminalIds(List.of(71L), List.of(81L), List.of(101L)),
                6, 7, 6, 7, new CoreFundingProgressView(8, false, 18, 2),
                new CoreSettlementProgressView(9, false, false, 72, 0, 1, 0), tombstones);
        CoreMessage envelope = new CoreMessage(command.header().exportEvent(7), CoreExportCodec.encodeEvent(event));

        CoreMessage restoredEnvelope = CoreMessageCodec.decode(CoreMessageCodec.encode(envelope));
        CoreExportEvent restored = CoreExportCodec.decodeEvent(restoredEnvelope, productLine);

        assertThat(restoredEnvelope.header().productLine()).isEqualTo(productLine);
        assertThat(restored.changedUsers().getFirst().productLine()).isEqualTo(productLine);
        assertThat(restored.changedOrders().getFirst().productLine()).isEqualTo(productLine);
        assertThat(restored.commandFingerprint()).isEqualTo(event.commandFingerprint());
        assertThat(restored.matcherEvidence()).isEqualTo(event.matcherEvidence());
        assertThat(restored.terminalIds()).isEqualTo(event.terminalIds());
        assertThat(restored.tombstones()).isEqualTo(tombstones);
        assertThat(restored.changedOrders()).containsExactly(recreated);
        ProductLine mismatch = productLine == ProductLine.SPOT
                ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(restoredEnvelope, mismatch))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("Core export event envelope mismatch");
    }

    @Test
    void preservesDeterministicFirstTouchOrderAndRejectsDuplicateTerminalAndTombstoneKeys() {
        assertThat(new CoreExportEvent.TerminalIds(List.of(2L, 1L), List.of(), List.of()).orderIds())
                .containsExactly(2L, 1L);
        assertThatThrownBy(() -> new CoreExportEvent.TerminalIds(List.of(1L, 1L), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreExportEvent.TerminalIds(List.of(0L), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreExportEvent.Tombstones(List.of(),
                List.of(new CoreExportEvent.UserAssetKey(1, "USDT"),
                        new CoreExportEvent.UserAssetKey(1, "USDT")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        CoreOrderStateView recreated = new CoreOrderStateView(71, ProductLine.SPOT, 17, "BTC-USDT", 3,
                CoreOrderSide.BUY, 60_000, 2, 0, 2, false, "OPEN", 2);
        var unresolved = new CoreExportEvent.Tombstones(List.of(), List.of(), List.of(), List.of(71L),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> event(ProductLine.SPOT, 1, UUID.randomUUID(), List.of(),
                List.of(recreated), unresolved))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Core Fact delete/recreate keys are unresolved");
    }

    @Test
    void exposesOnlyTheCompleteV10TypedConstructor() {
        assertThat(CoreExportEvent.class.getDeclaredConstructors()).singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterCount()).isEqualTo(36));
    }

    @Test
    void rejectsAZeroCommandFingerprint() {
        CoreExportEvent valid = event(ProductLine.SPOT, 1, UUID.randomUUID(), List.of(), List.of(),
                CoreExportEvent.Tombstones.empty());

        assertThatThrownBy(() -> new CoreExportEvent(valid.exportSequence(), valid.appliedCommandCount(),
                valid.businessStateHash(), valid.commandId(), valid.commandType(), valid.commandStatus(),
                valid.resultCode(), valid.userId(), valid.commandPayload(), valid.changedUsers(),
                valid.changedOrders(), valid.executions(), valid.fundingPayments(), valid.changedLiquidations(),
                valid.changedTreasuryAssets(), valid.changedTriggerOrders(), valid.beforeBusinessStateHash(),
                valid.beforeFundsStateHash(), valid.fundsStateHash(), valid.routeVersion(), valid.topologyHash(),
                valid.laneRevisionHash(), valid.committedCoreSequence(), valid.matcherTransition(),
                valid.clusterPosition(), valid.fundsPostings(),
                CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]), valid.matcherEvidence(),
                valid.terminalIds(), valid.previousCoreSequence(), valid.coreSequence(),
                valid.previousProjectionSequence(), valid.projectionSequence(), valid.fundingProgress(),
                valid.settlementProgress(), valid.tombstones()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command fingerprint must not be zero");
    }

    @Test
    void rejectsMixedTriggerProductLineAtConstructionAndBeforeConsumerDecode() {
        CoreTriggerOrderStateView perpetualTrigger = trigger(ProductLine.LINEAR_PERPETUAL);
        CoreUserStateView spotUser = new CoreUserStateView(ProductLine.SPOT, 17, 1,
                CorePositionMode.ONE_WAY, List.of(), List.of(), List.of());

        assertThatThrownBy(() -> event(ProductLine.SPOT, 1, UUID.randomUUID(), List.of(spotUser), List.of(),
                List.of(perpetualTrigger), CoreExportEvent.Tombstones.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mixed product lines");

        UUID commandId = UUID.randomUUID();
        CoreExportEvent triggerOnly = event(ProductLine.SPOT, 1, commandId, List.of(), List.of(),
                List.of(perpetualTrigger), CoreExportEvent.Tombstones.empty());
        CoreMessage envelope = new CoreMessage(command(ProductLine.SPOT, 1, commandId, 0, new byte[0])
                .header().exportEvent(1), CoreExportCodec.encodeEvent(triggerOnly));

        assertThatThrownBy(() -> CoreExportCodec.decodeEvent(envelope, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("Core export trigger order product line mismatch");
    }

    private static CoreMessage messageForBatchLength(int batchLength) {
        int payloadLength = batchLength - Integer.BYTES * 2 - CoreProtocol.HEADER_LENGTH;
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 1, 1, 0, 1, 1),
                new byte[payloadLength]);
        assertThat(CoreExportCodec.encodeBatch(List.of(message))).hasSize(batchLength);
        return message;
    }

    private static CoreExportEvent event(ProductLine productLine, long sequence, UUID commandId,
                                         List<CoreUserStateView> users, List<CoreOrderStateView> orders,
                                         CoreExportEvent.Tombstones tombstones) {
        return event(productLine, sequence, commandId, users, orders, List.of(), tombstones);
    }

    private static CoreExportEvent event(ProductLine productLine, long sequence, UUID commandId,
                                         List<CoreUserStateView> users, List<CoreOrderStateView> orders,
                                         List<CoreTriggerOrderStateView> triggers,
                                         CoreExportEvent.Tombstones tombstones) {
        CoreMessage command = command(productLine, sequence, commandId, 0, new byte[0]);
        return new CoreExportEvent(sequence, sequence, sequence, commandId,
                CoreMessageType.PROBE_INCREMENT, ResponseStatus.APPLIED, CoreResultCode.NONE, 0,
                command.payloadUnsafe(), users, orders, List.of(), List.of(), List.of(), List.of(), triggers,
                Math.max(0, sequence - 1), sequence, sequence + 1, CoreRoute.DEFAULT.version(), 31, 32,
                sequence, CoreMatcherTransition.unchanged(0, 0), sequence, List.of(),
                CommandFingerprint.of(command), List.of(), CoreExportEvent.TerminalIds.empty(),
                Math.max(0, sequence - 1), sequence, Math.max(0, sequence - 1), sequence,
                null, null, tombstones);
    }

    private static CoreTriggerOrderStateView trigger(ProductLine productLine) {
        return new CoreTriggerOrderStateView(501, productLine, 1001, "tp-501", "", "BTC-USDT",
                CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                CoreTriggerCondition.GREATER_OR_EQUAL, 70_000, 0, 0, 0, 0, 0, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "trigger-trace", 0, 0, 1_000, 1_000, 1,
                7, -25, 40);
    }

    private static CoreMessage command(ProductLine productLine, long sequence, UUID commandId,
                                       long userId, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                productLine, CommandSource.OPERATIONS, 1, sequence, userId, sequence, sequence), payload);
    }

    private static byte[] withStatusHeader(CoreExportStatus status, byte[] batch) {
        byte[] empty = CoreExportCodec.encodeBatchWithStatus(status, List.of());
        byte[] encoded = Arrays.copyOf(empty, CoreExportCodec.BATCH_STATUS_FIXED_LENGTH + batch.length);
        System.arraycopy(batch, 0, encoded, CoreExportCodec.BATCH_STATUS_FIXED_LENGTH, batch.length);
        return encoded;
    }
}
