package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
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

public final class ClusterSpotMatchSmokeMain {

    private ClusterSpotMatchSmokeMain() {
    }

    public static void main(String[] args) {
        List<String> hosts = Arrays.stream(System.getProperty("surprising.aeron.hostnames").split(","))
                .map(String::trim).toList();
        String egress = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        long seed = Long.parseLong(System.getProperty("surprising.aeron.smoke-seed", "3001"));
        long sourceId = 50_000 + seed;
        long seller = 6_000_000_000L + seed;
        long buyer = 7_000_000_000L + seed;
        long sellOrder = 4_000_000_000L + seed;
        long buyOrder = 5_000_000_000L + seed;
        boolean verify = "verify".equalsIgnoreCase(System.getProperty("surprising.aeron.smoke-mode", "execute"));
        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                ProductLine.SPOT, hosts, egress, Duration.ofSeconds(10))) {
            if (!verify) {
                applied(client, instrumentCommand(sourceId + 1_000_000, seed));
                applied(client, command(sourceId, seed, seller, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 5))));
                applied(client, command(sourceId, seed + 1, buyer, CoreMessageType.ADJUST_BALANCE,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500))));
                applied(client, command(sourceId, seed + 2, seller, CoreMessageType.PLACE_ORDER,
                        order(sellOrder, CoreOrderSide.SELL, 5, "BTC", 5)));
                applied(client, command(sourceId, seed + 3, buyer, CoreMessageType.PLACE_ORDER,
                        order(buyOrder, CoreOrderSide.BUY, 5, "USDT", 500)));
            }
            var sellerView = user(client, sourceId, seller, seed + 10);
            var buyerView = user(client, sourceId, buyer, seed + 11);
            requireBalance(sellerView, "BTC", 0, 0);
            requireBalance(sellerView, "USDT", 500, 0);
            requireBalance(buyerView, "BTC", 5, 0);
            requireBalance(buyerView, "USDT", 0, 0);
            String label = verify ? "spotMatchRecovery" : "spotMatchSmoke";
            System.out.printf("%s=PASS seller=%d buyer=%d btcTotal=5 usdtTotal=500%n", label, seller, buyer);
        }
    }

    private static CoreMessage instrumentCommand(long sourceId, long sequence) {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        return command(sourceId, sequence, 1, CoreMessageType.UPSERT_INSTRUMENT,
                TradingCommandCodec.encodeUpsertInstrument(instrument));
    }

    private static byte[] order(long orderId, CoreOrderSide side, long quantity, String asset, long reserved) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "BTC-USDT", 1,
                "BTC", "USDT", "USDT", side, 100, 100, 100, 100, quantity, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, ReservationKind.SPOT_ASSET, asset, reserved,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", 0, 0));
    }

    private static CoreMessage command(long sourceId, long sequence, long userId,
                                       CoreMessageType type, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, sourceId, sequence, userId, sequence, sequence), payload);
    }

    private static void applied(SurprisingAeronClient client, CoreMessage command) {
        var result = client.submit(command);
        if (result.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(command.header().messageType() + " rejected: " + result.resultCode());
        }
    }

    private static com.surprising.aeron.protocol.CoreUserStateView user(
            SurprisingAeronClient client, long sourceId, long userId, long correlation) {
        var response = client.submit(new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_QUERY,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, sourceId, 0,
                userId, correlation, correlation), new byte[0]));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException("user query failed userId=" + userId);
        }
        return CoreStateQueryCodec.decodeUserState(response.data());
    }

    private static void requireBalance(com.surprising.aeron.protocol.CoreUserStateView user,
                                       String asset, long available, long locked) {
        var balance = user.balances().stream().filter(value -> value.asset().equals(asset)).findFirst()
                .orElseThrow(() -> new IllegalStateException("missing balance " + asset));
        if (balance.availableUnits() != available || balance.lockedUnits() != locked) {
            throw new IllegalStateException("balance mismatch " + balance);
        }
    }
}
