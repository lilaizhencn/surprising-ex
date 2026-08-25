package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class ClusterFundsSmokeMain {

    private ClusterFundsSmokeMain() {
    }

    public static void main(String[] args) {
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "SPOT"));
        if (productLine != ProductLine.SPOT) {
            throw new IllegalArgumentException("P2 funds smoke currently validates SPOT only");
        }
        List<String> hostnames = Arrays.stream(System.getProperty(
                "surprising.aeron.hostnames", "localhost,localhost,localhost").split(","))
                .map(String::trim)
                .toList();
        String egressHostname = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        long configuredSourceId = Long.parseLong(System.getProperty("surprising.aeron.source-id", "9001"));
        long seed = Long.parseLong(System.getProperty(
                "surprising.aeron.smoke-seed", Long.toString(System.currentTimeMillis())));
        long userId = Math.addExact(9_000_000_000L, Math.floorMod(seed, 1_000_000_000L));
        long orderId = Math.addExact(8_000_000_000L, Math.floorMod(seed, 1_000_000_000L));
        long sourceId = Math.addExact(configuredSourceId, Math.floorMod(seed, 1_000_000_000L));
        long fundedUnits = 10_000;
        long reservedUnits = 2_500;
        boolean verifyOnly = "verify".equalsIgnoreCase(
                System.getProperty("surprising.aeron.smoke-mode", "execute"));

        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                productLine, hostnames, egressHostname, Duration.ofSeconds(10))) {
            if (verifyOnly) {
                verifyReleasedFunds(client, productLine, sourceId, userId, seed, fundedUnits);
                System.out.printf("fundsRecovery=PASS productLine=%s userId=%d totalUnits=%d lockedUnits=0%n",
                        productLine, userId, fundedUnits);
                return;
            }
            submitApplied(client, command(productLine, sourceId + 1_000_000, seed, 1,
                    CoreMessageType.UPSERT_INSTRUMENT,
                    TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                            "BTC-USDT", 1, ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0))));
            submitApplied(client, command(productLine, sourceId, seed, userId, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand("USDT", fundedUnits))));
            submitApplied(client, command(productLine, sourceId, seed + 1, userId, CoreMessageType.PLACE_ORDER,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1, CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, ""))));
            var reserved = queryUser(client, productLine, sourceId, userId, seed + 2);
            var reservedBalance = reserved.balances().stream()
                    .filter(value -> value.asset().equals("USDT"))
                    .findFirst().orElseThrow();
            if (reservedBalance.availableUnits() != fundedUnits - reservedUnits
                    || reservedBalance.lockedUnits() != reservedUnits) {
                throw new IllegalStateException("reservation funds mismatch: " + reservedBalance);
            }
            submitApplied(client, command(productLine, sourceId, seed + 3, userId, CoreMessageType.CANCEL_ORDER,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId))));
            verifyReleasedFunds(client, productLine, sourceId, userId, seed + 4, fundedUnits);
            System.out.printf("fundsSmoke=PASS productLine=%s userId=%d orderId=%d totalUnits=%d lockedUnits=0%n",
                    productLine, userId, orderId, fundedUnits);
        }
    }

    private static void verifyReleasedFunds(
            SurprisingAeronClient client,
            ProductLine productLine,
            long sourceId,
            long userId,
            long correlationId,
            long fundedUnits) {
        var released = queryUser(client, productLine, sourceId, userId, correlationId);
        var releasedBalance = released.balances().stream()
                .filter(value -> value.asset().equals("USDT"))
                .findFirst().orElseThrow();
        if (releasedBalance.availableUnits() != fundedUnits || releasedBalance.lockedUnits() != 0) {
            throw new IllegalStateException("release funds mismatch: " + releasedBalance);
        }
    }

    private static void submitApplied(SurprisingAeronClient client, CoreMessage command) {
        var result = client.submit(command);
        if (result.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException("command rejected: " + command.header().messageType());
        }
    }

    private static com.surprising.aeron.protocol.CoreUserStateView queryUser(
            SurprisingAeronClient client,
            ProductLine productLine,
            long sourceId,
            long userId,
            long correlationId) {
        CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_QUERY,
                UUID.randomUUID(), productLine, CommandSource.OPERATIONS, sourceId, 0, userId,
                correlationId, correlationId), new byte[0]);
        var result = client.submit(query);
        if (result.status() != ResponseStatus.OK) {
            throw new IllegalStateException("user state query failed");
        }
        return CoreStateQueryCodec.decodeUserState(result.data());
    }

    private static CoreMessage command(
            ProductLine productLine,
            long sourceId,
            long sourceSequence,
            long userId,
            CoreMessageType messageType,
            byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(messageType, UUID.randomUUID(), productLine,
                CommandSource.OPERATIONS, sourceId, sourceSequence, userId,
                sourceSequence, sourceSequence), payload);
    }
}
