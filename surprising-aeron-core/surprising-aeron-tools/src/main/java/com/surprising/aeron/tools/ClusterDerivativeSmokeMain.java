package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
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

public final class ClusterDerivativeSmokeMain {

    private ClusterDerivativeSmokeMain() {
    }

    public static void main(String[] args) {
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "LINEAR_PERPETUAL"));
        if (productLine != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalArgumentException("P4 derivative smoke validates LINEAR_PERPETUAL only");
        }
        List<String> hosts = Arrays.stream(System.getProperty("surprising.aeron.hostnames").split(","))
                .map(String::trim).toList();
        String egress = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        long seed = Long.parseLong(System.getProperty("surprising.aeron.smoke-seed", "5001"));
        long sourceId = 60_000 + seed;
        long longUser = 6_100_000_000L + seed;
        long shortUser = 7_100_000_000L + seed;
        long buyOrder = 4_100_000_000L + seed;
        long sellOrder = 5_100_000_000L + seed;
        long settlementId = 8_100_000_000L + seed;
        boolean verify = "verify".equalsIgnoreCase(System.getProperty("surprising.aeron.smoke-mode", "execute"));

        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                productLine, hosts, egress, Duration.ofSeconds(10))) {
            if (!verify) {
                applied(client, command(productLine, sourceId + 1_000_000, seed, 1,
                        CoreMessageType.UPSERT_INSTRUMENT,
                        TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                                "BTC-USDT", 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                                "BTC", "USDT", "USDT", 1, 1, 1,
                                100_000, 50_000, 0, 0, 0, -1, 0))));
                applied(client, command(productLine, sourceId, seed, longUser, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000))));
                applied(client, command(productLine, sourceId, seed + 1, shortUser, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000))));
                applied(client, command(productLine, sourceId, seed + 2, shortUser, CoreMessageType.PLACE_ORDER,
                        derivativeOrder(sellOrder, CoreOrderSide.SELL)));
                applied(client, command(productLine, sourceId, seed + 3, longUser, CoreMessageType.PLACE_ORDER,
                        derivativeOrder(buyOrder, CoreOrderSide.BUY)));
                applied(client, command(productLine, sourceId, seed + 4, 1, CoreMessageType.APPLY_MARK_PRICE,
                        TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                                "BTC-USDT", 1, 100, seed, 1_700_000_000_000L))));
                applied(client, command(productLine, sourceId, seed + 5, 1, CoreMessageType.APPLY_FUNDING,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                                settlementId, "BTC-USDT", 1, 10_000))));
            }

            var longView = user(client, productLine, sourceId, longUser, seed + 10);
            var shortView = user(client, productLine, sourceId, shortUser, seed + 11);
            requirePosition(longView, 10, 100, 100);
            requirePosition(shortView, -10, 100, 100);
            requireTotal(longView, 990);
            requireTotal(shortView, 1_010);
            if (!verify) {
                rejected(client, command(productLine, sourceId, seed + 12, 1, CoreMessageType.APPLY_FUNDING,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                                settlementId, "BTC-USDT", 1, 10_000))));
            }

            String label = verify ? "derivativeRecovery" : "derivativeSmoke";
            System.out.printf("%s=PASS productLine=%s longUser=%d shortUser=%d usdtTotal=2000 fundingNet=0%n",
                    label, productLine, longUser, shortUser);
        }
    }

    private static byte[] derivativeOrder(long orderId, CoreOrderSide side) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1, side, 100, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, ""));
    }

    private static void requirePosition(
            com.surprising.aeron.protocol.CoreUserStateView user,
            long quantity,
            long entryPrice,
            long margin) {
        var position = user.positions().stream().filter(value -> value.symbol().equals("BTC-USDT"))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing derivative position"));
        if (position.signedQuantitySteps() != quantity || position.entryPriceTicks() != entryPrice
                || position.positionMarginUnits() != margin) {
            throw new IllegalStateException("position mismatch " + position);
        }
    }

    private static void requireTotal(com.surprising.aeron.protocol.CoreUserStateView user, long expected) {
        var balance = user.balances().stream().filter(value -> value.asset().equals("USDT"))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing USDT balance"));
        long total = Math.addExact(balance.availableUnits(), balance.lockedUnits());
        if (total != expected) {
            throw new IllegalStateException("balance total mismatch " + balance);
        }
    }

    private static com.surprising.aeron.protocol.CoreUserStateView user(
            SurprisingAeronClient client,
            ProductLine productLine,
            long sourceId,
            long userId,
            long correlation) {
        var response = client.submit(new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_QUERY,
                UUID.randomUUID(), productLine, CommandSource.OPERATIONS, sourceId, 0,
                userId, correlation, correlation), new byte[0]));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException("user query failed userId=" + userId);
        }
        return CoreStateQueryCodec.decodeUserState(response.data());
    }

    private static CoreMessage command(
            ProductLine productLine,
            long sourceId,
            long sequence,
            long userId,
            CoreMessageType type,
            byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), productLine,
                CommandSource.OPERATIONS, sourceId, sequence, userId, sequence, sequence), payload);
    }

    private static void applied(SurprisingAeronClient client, CoreMessage command) {
        var result = client.submit(command);
        if (result.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(command.header().messageType() + " rejected: " + result.resultCode());
        }
    }

    private static void rejected(SurprisingAeronClient client, CoreMessage command) {
        var result = client.submit(command);
        if (result.commandStatus() != ResponseStatus.REJECTED) {
            throw new IllegalStateException(command.header().messageType() + " was not rejected");
        }
    }
}
