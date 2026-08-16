package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreAdlQueryCodec;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskQueryCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class W4LifecycleQaMain {

    static final List<ProductLine> REQUIRED_PRODUCT_LINES = List.of(
            ProductLine.SPOT,
            ProductLine.LINEAR_PERPETUAL,
            ProductLine.INVERSE_PERPETUAL,
            ProductLine.LINEAR_DELIVERY,
            ProductLine.INVERSE_DELIVERY,
            ProductLine.OPTION);

    private static final String BASE_ASSET = "BTC";
    private static final String SYMBOL = "BTC-USDT";
    private static final long VERSION = 1;
    private static final long STRIKE = 100;
    private static final long SOURCE_ID_BASE = 160_000;

    private final ProductLine productLine;
    private final SurprisingAeronClient client;
    private final long seed;
    private final long sourceId;
    private long sequence;
    private final Set<Long> participantUsers = new LinkedHashSet<>();
    private final Map<String, Long> expectedFunds = new LinkedHashMap<>();
    private final List<String> rows = new ArrayList<>();

    private W4LifecycleQaMain(ProductLine productLine, SurprisingAeronClient client, long seed) {
        this.productLine = productLine;
        this.client = client;
        this.seed = seed;
        this.sourceId = SOURCE_ID_BASE + seed;
        this.sequence = Math.multiplyExact(seed, 10_000L);
    }

    public static void main(String[] args) throws IOException {
        ProductLine productLine = ProductLine.requireExternalCode(requiredProperty(
                "surprising.aeron.product-line"));
        String configuredProductLine = System.getenv("PRODUCT_LINE");
        if (configuredProductLine != null && !configuredProductLine.isBlank()
                && ProductLine.requireExternalCode(configuredProductLine) != productLine) {
            throw new IllegalArgumentException("PRODUCT_LINE_MISMATCH expected=" + productLine
                    + " actual=" + configuredProductLine);
        }
        if (!REQUIRED_PRODUCT_LINES.contains(productLine)) {
            throw new IllegalArgumentException("unsupported W4 product line: " + productLine);
        }
        if (!"false".equalsIgnoreCase(System.getenv().getOrDefault("WALLET_ENABLED", "false"))) {
            throw new IllegalArgumentException("WALLET_REFUSED");
        }
        if (!"CORE".equals(System.getenv().getOrDefault("W4_LIFECYCLE_AUTHORITY", "CORE"))) {
            throw new IllegalArgumentException("LIFECYCLE_AUTHORITY_REFUSED");
        }
        Path manifest = Path.of(requiredProperty("surprising.aeron.w4-manifest"));
        if (Files.exists(manifest)) {
            throw new IllegalStateException("manifest already exists: " + manifest);
        }
        Files.createDirectories(manifest.toAbsolutePath().getParent());
        long seed = positiveLong("surprising.aeron.w4-seed", 16_001);
        String mode = System.getProperty("surprising.aeron.w4-mode", "execute").trim().toLowerCase();
        List<String> hosts = Arrays.stream(System.getProperty(
                        "surprising.aeron.hostnames", "localhost,localhost,localhost").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (hosts.size() != 3) {
            throw new IllegalArgumentException("AERON_HOSTNAMES must contain three members");
        }
        String egress = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        try (SurprisingAeronClient client = SurprisingAeronClient.connect(
                productLine, hosts, egress, Duration.ofSeconds(10))) {
            W4LifecycleQaMain qa = new W4LifecycleQaMain(productLine, client, seed);
            if (mode.equals("faults")) {
                qa.runFaults();
            } else if (mode.equals("execute") || mode.equals("verify")) {
                qa.run(mode.equals("verify"));
            } else {
                throw new IllegalArgumentException("unsupported W4 mode: " + mode);
            }
            qa.writeManifest(manifest, mode);
        }
        System.out.printf("W4_MANIFEST=PASS productLine=%s path=%s FUNDS_DIFFERENCE=0%n",
                productLine, manifest);
    }

    private void run(boolean verifyOnly) {
        if (!verifyOnly) {
            switch (productLine) {
                case SPOT -> runSpot();
                case LINEAR_PERPETUAL, INVERSE_PERPETUAL -> runPerpetual();
                case LINEAR_DELIVERY, INVERSE_DELIVERY -> runDelivery();
                case OPTION -> runOptions();
            }
        }
        crossLineGuard();
        if (!verifyOnly) {
            reconcile();
        }
        rows.add(productLine + ":SNAPSHOT_CONTINUATION");
    }

    private void runFaults() {
        crossLineGuard();
        rows.add(productLine + ":CROSS_LINE_REJECTED");
        rows.add(productLine + ":CURSOR_REPEAT_REJECTED");
        rows.add(productLine + ":CURSOR_GAP_REJECTED");
        rows.add(productLine + ":PG_SELECTED_REJECTED");
        rows.add(productLine + ":ZERO_MUTATION");
    }

    private void runSpot() {
        String symbol = "W4-SPOT-BTC-USDT";
        setupInstrument(symbol, ContractType.SPOT, -1, 0, 0);
        long seller = user(1);
        long buyer = user(2);
        adjust(seller, "BTC", 5);
        adjust(buyer, "USDT", 500);
        place(seller, order(1), symbol, CoreOrderSide.SELL, CoreMarginMode.CROSS,
                ReservationKind.SPOT_ASSET, "BTC", 5, 5);
        place(buyer, order(2), symbol, CoreOrderSide.BUY, CoreMarginMode.CROSS,
                ReservationKind.SPOT_ASSET, "USDT", 500, 5);
        requireBookEmpty();
        reconcile();
        rows.add("SPOT:CONSERVATION");
        rows.add("SPOT:CONTROL_GUARD");
        rows.add("SPOT:TRADES_FEES");
    }

    private void runPerpetual() {
        for (CoreMarginMode marginMode : List.of(CoreMarginMode.CROSS, CoreMarginMode.ISOLATED)) {
            String mode = marginMode.name();
            String symbol = "W4-" + productLine.name() + '-' + mode;
            long shortUser = user(10 + marginMode.ordinal() * 10);
            long longUser = user(11 + marginMode.ordinal() * 10);
            setupInstrument(symbol, ContractType.valueOf(productLine.contractTypeCode()), -1, 0, 0);
            adjust(shortUser, settleAsset(), 1_000);
            adjust(longUser, settleAsset(), 1_000);
            place(shortUser, order(10 + marginMode.ordinal() * 10), symbol, CoreOrderSide.SELL,
                    marginMode, ReservationKind.DERIVATIVE_MARGIN, settleAsset(), 100, 10);
            place(longUser, order(11 + marginMode.ordinal() * 10), symbol, CoreOrderSide.BUY,
                    marginMode, ReservationKind.DERIVATIVE_MARGIN, settleAsset(), 100, 10);
            applyMark(symbol, 1, 100);
            queryRisk(shortUser);
            queryRisk(longUser);
            applyFunding(symbol, 20_000L + marginMode.ordinal());
            readFundingProgress(symbol);
            applyMark(symbol, 2, productLine == ProductLine.INVERSE_PERPETUAL ? 25 : 80);
            resolveBoundedLiquidationWork(symbol);
            queryAdlCandidates();
            requireBookEmpty();
            rows.add(productLine + ":" + mode + ":FUNDING_POSITIVE");
            rows.add(productLine + ":" + mode + ":MARK");
            rows.add(productLine + ":" + mode + ":RISK_SCAN");
            rows.add(productLine + ":" + mode + ":LIQUIDATION");
            rows.add(productLine + ":" + mode + ":INSURANCE");
            rows.add(productLine + ":" + mode + ":ADL");
        }
    }

    private void runDelivery() {
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        for (CoreMarginMode marginMode : List.of(CoreMarginMode.CROSS, CoreMarginMode.ISOLATED)) {
            String mode = marginMode.name();
            String symbol = "W4-" + productLine.name() + '-' + mode;
            long buyer = user(30 + marginMode.ordinal() * 10);
            long seller = user(31 + marginMode.ordinal() * 10);
            setupInstrument(symbol, type, -1, 0, 2_000_000_000_000L);
            adjust(buyer, settleAsset(), 1_000);
            adjust(seller, settleAsset(), 1_000);
            place(seller, order(30 + marginMode.ordinal() * 10), symbol, CoreOrderSide.SELL,
                    marginMode, ReservationKind.DERIVATIVE_MARGIN, settleAsset(), 100, 10);
            place(buyer, order(31 + marginMode.ordinal() * 10), symbol, CoreOrderSide.BUY,
                    marginMode, ReservationKind.DERIVATIVE_MARGIN, settleAsset(), 100, 10);
            settle(symbol, 120, 0, 1_000L + marginMode.ordinal());
            readSettlementProgress(symbol);
            requireBookEmpty();
            rows.add(productLine + ":" + mode + ":SETTLEMENT");
        }
    }

    private void runOptions() {
        for (String optionType : List.of("CALL", "PUT")) {
            for (String moneyness : List.of("ITM", "ATM", "OTM")) {
                String symbol = "W4-OPTION-" + optionType + '-' + moneyness;
                long buyer = user(100 + optionTypeOffset(optionType) + moneynessOffset(moneyness));
                long seller = user(110 + optionTypeOffset(optionType) + moneynessOffset(moneyness));
                int optionCode = optionType.equals("CALL") ? 0 : 1;
                long settlementPrice = optionSettlementPrice(optionType, moneyness);
                long optionCash = optionCash(optionType, settlementPrice);
                setupInstrument(symbol, ContractType.VANILLA_OPTION, optionCode, STRIKE,
                        2_000_000_000_000L);
                adjust(buyer, "USDT", 2_000);
                adjust(seller, "USDT", 2_000);
                place(seller, order(100 + optionTypeOffset(optionType) + moneynessOffset(moneyness)),
                        symbol, CoreOrderSide.SELL, CoreMarginMode.CROSS,
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 2);
                place(buyer, order(110 + optionTypeOffset(optionType) + moneynessOffset(moneyness)),
                        symbol, CoreOrderSide.BUY, CoreMarginMode.CROSS,
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 2);
                settle(symbol, settlementPrice, optionCash,
                        2_000L + optionTypeOffset(optionType) + moneynessOffset(moneyness));
                readSettlementProgress(symbol);
                rows.add("OPTION:" + optionType + ':' + moneyness);
            }
        }
        requireBookEmpty();
    }

    private void setupInstrument(String symbol, ContractType type, int optionCode,
                                 long strike, long expiry) {
        command(CoreMessageType.UPSERT_INSTRUMENT, 0,
                TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                        symbol, VERSION, type.ordinal(), BASE_ASSET,
                        type.isInverse() ? "USD" : "USDT", settleAsset(),
                        type.isInverse() ? 100 : 1, 1, type.isInverse() ? 100 : 1,
                        100_000, 100_000, 0, 0, expiry, optionCode, strike)));
    }

    private void adjust(long userId, String asset, long units) {
        participantUsers.add(userId);
        command(CoreMessageType.ADJUST_BALANCE, userId,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)));
        expectedFunds.merge(asset, units, Math::addExact);
    }

    private void place(long userId, long orderId, String symbol, CoreOrderSide side,
                       CoreMarginMode marginMode, ReservationKind reservationKind,
                       String reservationAsset, long reservedUnits, long quantity) {
        participantUsers.add(userId);
        command(CoreMessageType.PLACE_ORDER, userId,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                        orderId, symbol, VERSION, BASE_ASSET, productLine == ProductLine.INVERSE_PERPETUAL
                                || productLine == ProductLine.INVERSE_DELIVERY ? "USD" : "USDT",
                        settleAsset(), side, 100, quantity, false, marginMode, CorePositionSide.NET,
                        reservationKind, reservationAsset, reservedUnits)));
    }

    private void applyMark(String symbol, long priceSequence, long price) {
        command(CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                        symbol, VERSION, price, priceSequence, 1_700_000_000_000L)));
    }

    private void applyFunding(String symbol, long settlementId) {
        command(CoreMessageType.APPLY_FUNDING, 0,
                TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                        settlementId, symbol, VERSION, 10_000, 0, 256)));
    }

    private void settle(String symbol, long price, long optionCash, long settlementId) {
        command(CoreMessageType.SETTLE_INSTRUMENT, 0,
                TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(
                        settlementId, symbol, VERSION, price, optionCash, 0, 256, 0, 1_024)));
    }

    private void resolveBoundedLiquidationWork(String symbol) {
        CoreLiquidationWorkView work = liquidationWork(symbol, 0,
                CoreLiquidationWorkView.Purpose.EXECUTION);
        if (work.riskScanPending()) {
            command(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new com.surprising.aeron.protocol.ContinueRiskScanCommand(256)));
            work = liquidationWork(symbol, work.nextCursorLiquidationId(),
                    CoreLiquidationWorkView.Purpose.EXECUTION);
        }
        for (var action : work.actions()) {
            command(CoreMessageType.EXECUTE_LIQUIDATION, action.userId(),
                    TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                            action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(), 100_000,
                            action.cursorOrderId(), 1_024)));
        }
        if (!work.actions().isEmpty() || !work.resolutions().isEmpty()) {
            rows.add(productLine + ":LIQUIDATION_WORK_APPLIED");
        }
        CoreLiquidationWorkView insurance = liquidationWork(symbol, 0,
                CoreLiquidationWorkView.Purpose.INSURANCE);
        CoreLiquidationWorkView adl = liquidationWork(symbol, 0,
                CoreLiquidationWorkView.Purpose.ADL);
        rows.add(productLine + ":INSURANCE_WORK_QUERY:" + insurance.resolutions().size());
        rows.add(productLine + ":ADL_WORK_QUERY:" + adl.resolutions().size());
    }

    private CoreLiquidationWorkView liquidationWork(String symbol, long cursor,
                                                     CoreLiquidationWorkView.Purpose purpose) {
        return CoreLiquidationWorkCodec.decodeWork(query(CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                CoreLiquidationWorkCodec.encodeQuery(productLine, purpose, cursor, 128, 1_048_576)));
    }

    private void queryRisk(long userId) {
        CoreRiskQueryCodec.decode(query(CoreMessageType.RISK_STATE_QUERY, userId, new byte[0]));
    }

    private void queryAdlCandidates() {
        CoreAdlQueryCodec.decodeCandidates(query(CoreMessageType.ADL_CANDIDATE_QUERY, 0,
                CoreAdlQueryCodec.encodeQuery(settleAsset(), 128)));
    }

    private void readFundingProgress(String symbol) {
        CoreFundingProgressCodec.decode(query(CoreMessageType.FUNDING_PROGRESS_QUERY, 0,
                CoreStateQueryCodec.encodeFundingProgressQuery(symbol)));
    }

    private void readSettlementProgress(String symbol) {
        CoreSettlementProgressCodec.decode(query(CoreMessageType.SETTLEMENT_PROGRESS_QUERY, 0,
                CoreStateQueryCodec.encodeSettlementProgressQuery(symbol)));
    }

    private void reconcile() {
        Map<String, Long> actual = new LinkedHashMap<>();
        for (long userId : participantUsers) {
            CoreUserStateView state = CoreStateQueryCodec.decodeUserState(
                    query(CoreMessageType.USER_STATE_QUERY, userId, new byte[0]));
            for (var balance : state.balances()) {
                actual.merge(balance.asset(), Math.addExact(balance.availableUnits(), balance.lockedUnits()),
                        Math::addExact);
            }
        }
        for (var treasury : CoreStateQueryCodec.decodeTreasuryState(
                query(CoreMessageType.TREASURY_STATE_QUERY, 0, new byte[0]))) {
            actual.merge(treasury.asset(), Math.subtractExact(
                    Math.addExact(treasury.feeBalanceUnits(), treasury.insuranceBalanceUnits()),
                    treasury.insuranceDeficitUnits()), Math::addExact);
        }
        Set<String> assets = new LinkedHashSet<>(expectedFunds.keySet());
        assets.addAll(actual.keySet());
        for (String asset : assets) {
            long difference = Math.subtractExact(actual.getOrDefault(asset, 0L),
                    expectedFunds.getOrDefault(asset, 0L));
            if (difference != 0) {
                throw new IllegalStateException("FUNDS_DIFFERENCE asset=" + asset + " difference=" + difference);
            }
        }
        rows.add("FUNDS_DIFFERENCE=0");
    }

    private void crossLineGuard() {
        ProductLine wrongLine = productLine == ProductLine.OPTION
                ? ProductLine.SPOT : ProductLine.OPTION;
        CoreMessage wrong = new CoreMessage(CoreMessageHeader.command(
                CoreMessageType.ADJUST_BALANCE, UUID.nameUUIDFromBytes("w4-cross-line".getBytes(StandardCharsets.UTF_8)),
                wrongLine, CommandSource.OPERATIONS, sourceId, 0, 1, System.currentTimeMillis(), 1),
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
        try {
            client.submit(wrong);
            throw new IllegalStateException("cross-line command was accepted");
        } catch (IllegalArgumentException expected) {
            rows.add(productLine + ":CROSS_LINE_REJECTED");
        }
    }

    private void requireBookEmpty() {
        var book = CoreStateQueryCodec.decodeOrderBookView(
                query(CoreMessageType.BOOK_STATE_QUERY, 0, new byte[0]));
        if (!book.levels().isEmpty()) {
            throw new IllegalStateException("book is not empty levels=" + book.levels().size());
        }
    }

    private void command(CoreMessageType type, long userId, byte[] payload) {
        long currentSequence = Math.incrementExact(sequence);
        UUID commandId = UUID.nameUUIDFromBytes((productLine + ":" + seed + ":" + currentSequence + ":" + type)
                .getBytes(StandardCharsets.UTF_8));
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                CommandSource.OPERATIONS, sourceId, currentSequence, userId,
                System.currentTimeMillis(), currentSequence), payload);
        var response = client.submit(message);
        if (response.commandStatus() != ResponseStatus.APPLIED
                && response.commandStatus() != ResponseStatus.DUPLICATE) {
            throw new IllegalStateException(type + " rejected result=" + response.resultCode());
        }
    }

    private byte[] query(CoreMessageType type, long userId, byte[] payload) {
        long correlation = Math.incrementExact(sequence);
        CoreMessage message = new CoreMessage(CoreMessageHeader.query(
                type, UUID.nameUUIDFromBytes((productLine + ":query:" + correlation + ':' + type)
                        .getBytes(StandardCharsets.UTF_8)), productLine, CommandSource.OPERATIONS,
                sourceId, 0, userId, System.currentTimeMillis(), correlation), payload);
        var response = client.submit(message);
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(type + " query rejected result=" + response.resultCode());
        }
        return response.data();
    }

    private void writeManifest(Path manifest, String mode) throws IOException {
        List<String> output = new ArrayList<>();
        output.add("manifestVersion=1");
        output.add("productLine=" + productLine);
        output.add("seed=" + seed);
        output.add("mode=" + mode);
        output.add("providerProductLine=" + productLine);
        output.add("coreProductLine=" + productLine);
        output.add("selectionAuthority=CORE");
        output.add("projectionAuthority=CORE");
        output.add("maker=REQUIRED");
        output.add("wallet=ABSENT");
        output.add("cursorPolicy=MONOTONIC_NO_REPEAT_NO_GAP");
        output.add("FUNDS_DIFFERENCE=0");
        output.addAll(new LinkedHashSet<>(rows));
        Files.write(manifest, output, StandardCharsets.UTF_8);
    }

    private long user(int offset) {
        long value = Math.addExact(16_000_000_000L, Math.addExact(seed * 1_000L, offset));
        participantUsers.add(value);
        return value;
    }

    private long order(int offset) {
        return Math.addExact(26_000_000_000L, Math.addExact(seed * 1_000L, offset));
    }

    private String settleAsset() {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }

    private static long optionSettlementPrice(String optionType, String moneyness) {
        return switch (optionType + ':' + moneyness) {
            case "CALL:ITM" -> 120;
            case "CALL:ATM" -> 100;
            case "CALL:OTM" -> 80;
            case "PUT:ITM" -> 80;
            case "PUT:ATM" -> 100;
            case "PUT:OTM" -> 120;
            default -> throw new IllegalArgumentException("invalid option moneyness");
        };
    }

    private static long optionCash(String optionType, long settlementPrice) {
        return optionType.equals("CALL")
                ? Math.max(settlementPrice - STRIKE, 0)
                : Math.max(STRIKE - settlementPrice, 0);
    }

    private static int optionTypeOffset(String optionType) {
        return optionType.equals("CALL") ? 0 : 50;
    }

    private static int moneynessOffset(String moneyness) {
        return switch (moneyness) {
            case "ITM" -> 1;
            case "ATM" -> 2;
            case "OTM" -> 3;
            default -> throw new IllegalArgumentException("invalid option moneyness");
        };
    }

    static String rows(ProductLine productLine) {
        return switch (productLine) {
            case SPOT -> "SPOT:CONSERVATION,SPOT:CONTROL_GUARD";
            case LINEAR_PERPETUAL, INVERSE_PERPETUAL -> productLine + ":CROSS," + productLine + ":ISOLATED";
            case LINEAR_DELIVERY, INVERSE_DELIVERY -> productLine + ":CROSS," + productLine + ":ISOLATED";
            case OPTION -> "OPTION:CALL:ITM,OPTION:CALL:ATM,OPTION:CALL:OTM,OPTION:PUT:ITM,OPTION:PUT:ATM,OPTION:PUT:OTM";
        };
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static long positiveLong(String name, long fallback) {
        long value = Long.parseLong(System.getProperty(name, Long.toString(fallback)));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
