package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskQueryCodec;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class ClusterProductLineGateMain {

    private static final String SYMBOL = "BTC-USDT";
    private static final long INITIAL_USER_UNITS = 1_000;
    private static final long LIQUIDATION_USER_UNITS = 180;
    private static final long MAKER_FEE_RATE_PPM = 1_000;
    private static final long TAKER_FEE_RATE_PPM = 2_000;

    private final ProductLine productLine;
    private final SurprisingAeronClient client;
    private final long sourceId;
    private final long seed;
    private long sequence;

    private ClusterProductLineGateMain(ProductLine productLine, SurprisingAeronClient client, long seed) {
        this.productLine = productLine;
        this.client = client;
        this.seed = seed;
        this.sourceId = 80_000 + seed;
        this.sequence = System.currentTimeMillis();
    }

    public static void main(String[] args) {
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "SPOT"));
        List<String> hosts = Arrays.stream(System.getProperty("surprising.aeron.hostnames").split(","))
                .map(String::trim).toList();
        String egress = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        long seed = Long.parseLong(System.getProperty("surprising.aeron.smoke-seed", "7001"));
        boolean verify = "verify".equalsIgnoreCase(
                System.getProperty("surprising.aeron.smoke-mode", "execute"));
        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                productLine, hosts, egress, Duration.ofSeconds(10))) {
            ClusterProductLineGateMain gate = new ClusterProductLineGateMain(productLine, client, seed);
            if (!verify) gate.execute();
            gate.verify();
            System.out.printf("productLineGate=PASS mode=%s productLine=%s fundsDiff=0 bookLevels=0 seed=%d%n",
                    verify ? "verify" : "execute", productLine, seed);
        }
    }

    private void execute() {
        applied(1, CoreMessageType.UPSERT_INSTRUMENT,
                TradingCommandCodec.encodeUpsertInstrument(instrument()));
        if (productLine == ProductLine.SPOT) {
            executeSpot();
            return;
        }
        executeDerivative();
    }

    private void executeSpot() {
        long seller = user(1);
        long buyer = user(2);
        applied(seller, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 5)));
        applied(buyer, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 600)));
        applied(seller, CoreMessageType.PLACE_ORDER, order(order(1), CoreOrderSide.SELL, 5, "BTC", 5));
        applied(buyer, CoreMessageType.PLACE_ORDER, order(order(2), CoreOrderSide.BUY, 5, "USDT", 600));
    }

    private void executeDerivative() {
        String settleAsset = settleAsset();
        long shortUser = user(1);
        long longUser = user(2);
        long initialUserUnits = initialUserUnits();
        adjust(shortUser, settleAsset, initialUserUnits);
        adjust(longUser, settleAsset, initialUserUnits);
        long shortReservation = productLine == ProductLine.OPTION ? 1_200 : 150;
        long longReservation = productLine == ProductLine.OPTION ? 1_100 : 150;
        applied(shortUser, CoreMessageType.PLACE_ORDER,
                order(order(1), CoreOrderSide.SELL, 10, settleAsset, shortReservation));
        applied(longUser, CoreMessageType.PLACE_ORDER,
                order(order(2), CoreOrderSide.BUY, 10, settleAsset, longReservation));

        if (isPerpetual()) {
            applied(1, CoreMessageType.APPLY_MARK_PRICE,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                            SYMBOL, 1, 100, 1, 1_700_000_000_000L)));
            applied(1, CoreMessageType.APPLY_FUNDING,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                            9_000_000_000L + seed, SYMBOL, 1, 10_000)));
            executeLiquidation(settleAsset);
            return;
        }

        long optionCash = productLine == ProductLine.OPTION ? 25 : 0;
        applied(1, CoreMessageType.SETTLE_INSTRUMENT,
                TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(
                        9_100_000_000L + seed, SYMBOL, 1, 120, optionCash)));
    }

    private void executeLiquidation(String settleAsset) {
        long shortUser = user(3);
        long longUser = user(4);
        adjust(shortUser, settleAsset, LIQUIDATION_USER_UNITS);
        adjust(longUser, settleAsset, LIQUIDATION_USER_UNITS);
        applied(shortUser, CoreMessageType.PLACE_ORDER,
                order(order(3), CoreOrderSide.SELL, 10, settleAsset, 150));
        applied(longUser, CoreMessageType.PLACE_ORDER,
                order(order(4), CoreOrderSide.BUY, 10, settleAsset, 150));
        long markPrice = productLine == ProductLine.INVERSE_PERPETUAL ? 25 : 80;
        long priceSequence = 2;
        applied(1, CoreMessageType.APPLY_MARK_PRICE,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                        SYMBOL, 1, markPrice, priceSequence, 1_700_000_000_000L)));
        var work = CoreLiquidationWorkCodec.decodeWork(query(CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                CoreLiquidationWorkCodec.encodeQuery(productLine,
                        com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.EXECUTION,
                        0, 100, 1_048_576)));
        var action = work.actions().stream().filter(value -> value.userId() == longUser).findFirst()
                .orElseThrow(() -> new IllegalStateException("missing liquidation work for user " + longUser));
        applied(longUser, CoreMessageType.EXECUTE_LIQUIDATION,
                TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                        action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(), 100_000)));
    }

    private void verify() {
        requireBookEmpty();
        if (productLine == ProductLine.SPOT) {
            requireBalance(userState(user(1)), "BTC", 0);
            requireBalance(userState(user(1)), "USDT", 499);
            requireBalance(userState(user(2)), "BTC", 5);
            requireBalance(userState(user(2)), "USDT", 99);
            return;
        }
        if (isPerpetual()) {
            requirePosition(userState(user(1)), -10);
            requirePosition(userState(user(2)), 10);
            requirePosition(userState(user(3)), -10);
            requirePosition(userState(user(4)), 0);
            var work = CoreLiquidationWorkCodec.decodeWork(query(CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                    CoreLiquidationWorkCodec.encodeQuery(productLine,
                            com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.EXECUTION,
                            0, 100, 1_048_576)));
            if (work.actions().stream().anyMatch(value -> value.userId() == user(4))) {
                throw new IllegalStateException("completed liquidation still returned as work");
            }
            requireEconomicFunds(2 * INITIAL_USER_UNITS + 2 * LIQUIDATION_USER_UNITS,
                    List.of(user(1), user(2), user(3), user(4)));
            return;
        }
        requirePosition(userState(user(1)), 0);
        requirePosition(userState(user(2)), 0);
        requireEconomicFunds(2 * initialUserUnits(), List.of(user(1), user(2)));
    }

    private long initialUserUnits() {
        return productLine == ProductLine.OPTION ? 2_000 : INITIAL_USER_UNITS;
    }

    private void requireEconomicFunds(long expected, List<Long> userIds) {
        long actual = 0;
        for (long userId : userIds) {
            actual = Math.addExact(actual, balanceTotal(userState(userId), settleAsset()));
            CoreRiskQueryCodec.decode(query(CoreMessageType.RISK_STATE_QUERY, userId, new byte[0]));
        }
        for (var treasury : CoreStateQueryCodec.decodeTreasuryState(
                query(CoreMessageType.TREASURY_STATE_QUERY, 0, new byte[0]))) {
            if (treasury.asset().equals(settleAsset())) {
                actual = Math.addExact(actual, treasury.feeBalanceUnits());
                actual = Math.addExact(actual, treasury.insuranceBalanceUnits());
            }
        }
        if (actual != expected) {
            throw new IllegalStateException("economic funds mismatch expected=" + expected + " actual=" + actual);
        }
    }

    private UpsertInstrumentCommand instrument() {
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        return new UpsertInstrumentCommand(SYMBOL, 1, type.ordinal(), "BTC", "USDT", settleAsset(),
                1, 1, type.isInverse() ? 1_000 : 1, 100_000, 50_000,
                MAKER_FEE_RATE_PPM, TAKER_FEE_RATE_PPM,
                expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
    }

    private byte[] order(long orderId, CoreOrderSide side, long quantity, String reservationAsset, long reserved) {
        ReservationKind kind = productLine == ProductLine.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, SYMBOL, 1,
                "BTC", "USDT", settleAsset(), side, 100, quantity, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, kind, reservationAsset, reserved, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, 100, false, "cluster-gate-" + orderId,
                MAKER_FEE_RATE_PPM, TAKER_FEE_RATE_PPM));
    }

    private void adjust(long userId, String asset, long units) {
        applied(userId, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)));
    }

    private void applied(long userId, CoreMessageType type, byte[] payload) {
        sequence = Math.incrementExact(sequence);
        long currentSequence = sequence;
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), productLine,
                CommandSource.OPERATIONS, sourceId, currentSequence, userId,
                currentSequence, currentSequence), payload);
        var response = client.submit(command);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(type + " rejected: " + response.resultCode()
                    + " sourceId=" + sourceId + " sourceSequence=" + currentSequence);
        }
    }

    private byte[] query(CoreMessageType type, long userId, byte[] payload) {
        sequence = Math.incrementExact(sequence);
        long correlation = sequence;
        CoreMessage query = new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), productLine,
                CommandSource.OPERATIONS, sourceId, 0, userId, correlation, correlation), payload);
        var response = client.submit(query);
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(type + " query failed: " + response.resultCode());
        }
        return response.data();
    }

    private CoreUserStateView userState(long userId) {
        return CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, userId, new byte[0]));
    }

    private void requireBookEmpty() {
        var book = OrderBookBootstrapLoader.load((type, payload) -> query(type, 0, payload));
        if (!book.levels().isEmpty()) throw new IllegalStateException("book is not empty: " + book.levels());
    }

    private static void requirePosition(CoreUserStateView state, long expectedQuantity) {
        long quantity = state.positions().stream().filter(value -> value.symbol().equals(SYMBOL))
                .mapToLong(value -> value.signedQuantitySteps()).sum();
        if (quantity != expectedQuantity) {
            throw new IllegalStateException("position mismatch user=" + state.userId()
                    + " expected=" + expectedQuantity + " actual=" + quantity);
        }
    }

    private static void requireBalance(CoreUserStateView state, String asset, long expectedTotal) {
        long actual = balanceTotal(state, asset);
        if (actual != expectedTotal) {
            throw new IllegalStateException("balance mismatch user=" + state.userId()
                    + " asset=" + asset + " expected=" + expectedTotal + " actual=" + actual);
        }
    }

    private static long balanceTotal(CoreUserStateView state, String asset) {
        return state.balances().stream().filter(value -> value.asset().equals(asset))
                .mapToLong(value -> Math.addExact(value.availableUnits(), value.lockedUnits())).sum();
    }

    private boolean isPerpetual() {
        return productLine == ProductLine.LINEAR_PERPETUAL || productLine == ProductLine.INVERSE_PERPETUAL;
    }

    private String settleAsset() {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }

    private long user(int offset) {
        return 10_000_000_000L + seed * 10 + offset;
    }

    private long order(int offset) {
        return 20_000_000_000L + seed * 10 + offset;
    }
}
