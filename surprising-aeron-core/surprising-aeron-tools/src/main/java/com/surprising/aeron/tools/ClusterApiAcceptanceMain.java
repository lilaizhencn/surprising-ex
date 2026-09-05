package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreRiskQueryCodec;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class ClusterApiAcceptanceMain {

    private static final long SPOT_BASE_UNITS = 20;
    private static final long SPOT_QUOTE_UNITS = 2_000;
    private static final long DERIVATIVE_UNITS = 5_000;
    private static final long TRADE_QUANTITY = 10;

    private final ProductLine productLine;
    private final SurprisingAeronClient client;
    private final long seed;
    private final long sourceId;
    private final String symbol;
    private final long instrumentVersion;
    private long sequence;

    private ClusterApiAcceptanceMain(
            ProductLine productLine,
            SurprisingAeronClient client,
            long seed,
            String symbol,
            long instrumentVersion) {
        this.productLine = productLine;
        this.client = client;
        this.seed = seed;
        this.sourceId = 280_000 + seed;
        this.symbol = symbol;
        this.instrumentVersion = instrumentVersion;
        this.sequence = System.currentTimeMillis();
    }

    public static void main(String[] args) {
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "SPOT"));
        List<String> hosts = Arrays.stream(System.getProperty(
                        "surprising.aeron.hostnames", "localhost,localhost,localhost").split(","))
                .map(String::trim).toList();
        String egress = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        long seed = Long.parseLong(System.getProperty("surprising.aeron.acceptance-seed", "9001"));
        String symbol = System.getProperty("surprising.aeron.symbol", "P8-BTC-USDT").trim().toUpperCase();
        long version = Long.parseLong(System.getProperty("surprising.aeron.instrument-version", "1"));
        String mode = System.getProperty("surprising.aeron.acceptance-mode", "setup").trim().toLowerCase();
        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                productLine, hosts, egress, Duration.ofSeconds(10))) {
            ClusterApiAcceptanceMain acceptance = new ClusterApiAcceptanceMain(
                    productLine, client, seed, symbol, version);
            switch (mode) {
                case "setup" -> acceptance.setup();
                case "verify" -> acceptance.verify(false);
                case "finalize" -> acceptance.finalizeProductLine();
                case "verify-final" -> acceptance.verify(true);
                default -> throw new IllegalArgumentException("unsupported acceptance mode: " + mode);
            }
            System.out.printf(
                    "apiAcceptance=PASS mode=%s productLine=%s symbol=%s seller=%d buyer=%d fundsDiff=0 seed=%d%n",
                    mode, productLine, symbol, acceptance.seller(), acceptance.buyer(), seed);
        }
    }

    private void setup() {
        applied(1, CoreMessageType.UPSERT_INSTRUMENT,
                TradingCommandCodec.encodeUpsertInstrument(instrument()));
        if (productLine == ProductLine.SPOT) {
            adjust(seller(), "BTC", SPOT_BASE_UNITS);
            adjust(buyer(), "USDT", SPOT_QUOTE_UNITS);
            return;
        }
        adjust(seller(), settleAsset(), DERIVATIVE_UNITS);
        adjust(buyer(), settleAsset(), DERIVATIVE_UNITS);
    }

    private void finalizeProductLine() {
        if (isPerpetual()) {
            applied(1, CoreMessageType.APPLY_MARK_PRICE,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                            symbol, instrumentVersion, 100, 19_000_000_000L + seed, 1_700_000_000_000L)));
            applied(1, CoreMessageType.APPLY_FUNDING,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                            19_000_000_000L + seed, symbol, instrumentVersion, 10_000)));
        } else if (isExpiring()) {
            applied(1, CoreMessageType.SETTLE_INSTRUMENT,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(
                            19_100_000_000L + seed, symbol, instrumentVersion, 120,
                            productLine == ProductLine.OPTION ? 25 : 0)));
        }
    }

    private void verify(boolean finalized) {
        requireBookEmpty();
        if (productLine == ProductLine.SPOT) {
            requireBalance(userState(seller()), "BTC", SPOT_BASE_UNITS - TRADE_QUANTITY);
            requireBalance(userState(seller()), "USDT", 1_000);
            requireBalance(userState(buyer()), "BTC", TRADE_QUANTITY);
            requireBalance(userState(buyer()), "USDT", SPOT_QUOTE_UNITS - 1_000);
            return;
        }
        long expectedShort = finalized && isExpiring() ? 0 : -TRADE_QUANTITY;
        long expectedLong = finalized && isExpiring() ? 0 : TRADE_QUANTITY;
        requirePosition(userState(seller()), expectedShort);
        requirePosition(userState(buyer()), expectedLong);
        requireEconomicFunds(2 * DERIVATIVE_UNITS, List.of(seller(), buyer()));
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
        return new UpsertInstrumentCommand(symbol, instrumentVersion, type.ordinal(), "BTC", "USDT", settleAsset(),
                1, 1, type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0,
                expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
    }

    private void adjust(long userId, String asset, long units) {
        applied(userId, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)));
    }

    private void applied(long userId, CoreMessageType type, byte[] payload) {
        sequence = Math.incrementExact(sequence);
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), productLine,
                CommandSource.OPERATIONS, sourceId, sequence, userId, sequence, sequence), payload);
        var response = client.submit(command);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(type + " rejected: " + response.resultCode());
        }
    }

    private byte[] query(CoreMessageType type, long userId, byte[] payload) {
        sequence = Math.incrementExact(sequence);
        CoreMessage query = new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), productLine,
                CommandSource.OPERATIONS, sourceId, 0, userId, sequence, sequence), payload);
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
        if (!book.levels().isEmpty()) {
            throw new IllegalStateException("book is not empty: " + book.levels());
        }
    }

    private void requirePosition(CoreUserStateView state, long expectedQuantity) {
        long actual = state.positions().stream().filter(value -> value.symbol().equals(symbol))
                .mapToLong(value -> value.signedQuantitySteps()).sum();
        if (actual != expectedQuantity) {
            throw new IllegalStateException("position mismatch user=" + state.userId()
                    + " expected=" + expectedQuantity + " actual=" + actual);
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

    private boolean isExpiring() {
        return productLine == ProductLine.LINEAR_DELIVERY
                || productLine == ProductLine.INVERSE_DELIVERY
                || productLine == ProductLine.OPTION;
    }

    private String settleAsset() {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }

    private long seller() {
        return 30_000_000_000L + seed * 10 + 1;
    }

    private long buyer() {
        return 30_000_000_000L + seed * 10 + 2;
    }
}
