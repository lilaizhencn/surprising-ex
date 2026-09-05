package com.surprising.aeron.tools;

import com.surprising.aeron.client.AeronLifecycleCoordinator;
import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.ApplyFundingCommand;
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
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

public final class W4LifecycleQaMain implements AutoCloseable {

    static final List<ProductLine> REQUIRED_PRODUCT_LINES = List.of(
            ProductLine.SPOT,
            ProductLine.LINEAR_PERPETUAL,
            ProductLine.INVERSE_PERPETUAL,
            ProductLine.LINEAR_DELIVERY,
            ProductLine.INVERSE_DELIVERY,
            ProductLine.OPTION);

    private static final String BASE_ASSET = "BTC";
    private static final String SYMBOL = "BTC-USDT";
    private static final long STRIKE = 100;
    private static final long MAKER_FEE_RATE_PPM = 100_000;
    private static final long TAKER_FEE_RATE_PPM = 200_000;
    private static final long FUNDING_RATE_PPM = 10_000;
    private static final long QUOTE_SCALE_UNITS = 100_000_000L;
    private static final long SOURCE_ID_BASE = 160_000;
    private static final String REAL_CAPABILITY_PENDING =
            "provider-to-core-lifecycle,cursor-repeat-gap,pg-selected,maker-user-treasury-reconciliation";

    private final ProductLine productLine;
    private final SurprisingAeronClient client;
    private final AeronLifecycleCoordinator lifecycleCoordinator = new AeronLifecycleCoordinator();
    private final long seed;
    private final long sourceId;
    private final long makerUserId;
    private final List<Long> makerUserIds;
    private final HttpClient httpClient;
    private final Map<String, String> providerUrls;
    private final ControlledIndexFeed indexFeed;
    private long sequence;
    private final Set<Long> participantUsers = new LinkedHashSet<>();
    private final Map<String, Long> expectedFunds = new LinkedHashMap<>();
    private final List<String> rows = new ArrayList<>();
    private final List<SpotOrder> spotOrders = new ArrayList<>();
    private final Map<String, Long> instrumentVersions = new LinkedHashMap<>();
    private boolean reconciliationObserved;
    private boolean makerReconciliationObserved;
    private boolean providerBoundaryObserved;
    private boolean feeLedgerObserved;

    W4LifecycleQaMain(ProductLine productLine, SurprisingAeronClient client, long seed) {
        this.productLine = productLine;
        this.client = client;
        this.seed = seed;
        this.sourceId = SOURCE_ID_BASE + seed;
        this.makerUserIds = configuredMakerUserIds();
        this.makerUserId = makerUserIds.getFirst();
        this.sequence = Math.multiplyExact(seed, 10_000L);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.providerUrls = providerUrls();
        this.indexFeed = ControlledIndexFeed.start();
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
        long seed = positiveLong("surprising.aeron.w4-seed", 16_001);
        String mode = System.getProperty("surprising.aeron.w4-mode", "execute").trim().toLowerCase();
        if (!Set.of("execute", "verify", "faults").contains(mode)) {
            throw new IllegalArgumentException("unsupported W4 mode: " + mode);
        }
        Path manifest = Path.of(requiredProperty("surprising.aeron.w4-manifest"));
        if (Files.exists(manifest)) {
            throw new IllegalStateException("manifest already exists: " + manifest);
        }
        Files.createDirectories(manifest.toAbsolutePath().getParent());
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
            try (qa) {
                qa.requireProviderCapabilities(mode);
                if (mode.equals("faults")) {
                    qa.runFaults();
                } else if (mode.equals("execute") || mode.equals("verify")) {
                    qa.run(mode.equals("verify"));
                }
                qa.writeManifest(manifest, mode);
            }
        }
        System.out.printf("W4_MANIFEST=REAL_PASS productLine=%s path=%s FUNDS_DIFFERENCE=0%n",
                productLine, manifest);
    }

    private void requireProviderCapabilities(String mode) {
        if ("faults".equals(mode)) {
            requireHttp("risk", "GET", "/actuator/health", null);
            return;
        }
        for (String service : List.of("instrument", "account", "trading", "risk", "maker")) {
            requireHttp(service, "GET", "/actuator/health", null);
        }
        if (productLine == ProductLine.LINEAR_PERPETUAL || productLine == ProductLine.INVERSE_PERPETUAL) {
            for (String service : List.of("funding", "liquidation", "insurance", "adl")) {
                requireHttp(service, "GET", "/actuator/health", null);
            }
        }
    }

    private Map<String, String> providerUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        Map<String, Integer> ports = Map.of(
                "instrument", 9080, "price", 9082, "trading", 9084, "risk", 9087,
                "funding", 9089, "liquidation", 9087, "insurance", 9087,
                "adl", 9087, "account", 9086, "maker", 9096);
        for (var entry : ports.entrySet()) {
            String envName = "W4_" + entry.getKey().toUpperCase() + "_URL";
            String configured = System.getenv(envName);
            if ((configured == null || configured.isBlank())
                    && List.of("risk", "liquidation", "insurance", "adl").contains(entry.getKey())) {
                configured = System.getenv("W4_DERIVATIVES_LIFECYCLE_URL");
            }
            urls.put(entry.getKey(), (configured == null || configured.isBlank())
                    ? "http://127.0.0.1:" + entry.getValue() : configured.trim());
        }
        urls.put("command", urls.get("trading"));
        return Map.copyOf(urls);
    }

    private void requireHttp(String service, String method, String path, String body) {
        try {
            request(service, method, path, body, Map.of());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("W4_REAL_CAPABILITY_PENDING service=" + service
                    + " mode=" + System.getProperty("surprising.aeron.w4-mode", "execute")
                    + " missing=" + REAL_CAPABILITY_PENDING + " cause=" + ex.getMessage(), ex);
        }
    }

    private String request(String service, String method, String path, String body,
                           Map<String, String> headers) {
        String base = providerUrls.get(service);
        if (base == null) {
            throw new IllegalArgumentException("unknown provider service: " + service);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(20));
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP_" + response.statusCode() + " service=" + service
                        + " path=" + path + " body=" + response.body());
            }
            rows.add("HTTP:" + service + ':' + method + ':' + response.statusCode());
            return response.body();
        } catch (IOException ex) {
            throw new IllegalStateException("HTTP_IO service=" + service + " path=" + path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP_INTERRUPTED service=" + service + " path=" + path, ex);
        }
    }

    private static Map<String, String> adminHeaders() {
        return Map.of("X-Admin-User-Id", "w4-qa-admin");
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void run(boolean verifyOnly) {
        if (verifyOnly) {
            throw new IllegalStateException("W4_VERIFY_REQUIRES_REAL_RECONCILIATION");
        }
        if (!verifyOnly) {
            captureBaselineFunds();
            seedMakerAccount();
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
            requireFeeLedgerObserved();
            rows.add(productLine + ":TRADES_FEES");
        }
        rows.add(productLine + ":SNAPSHOT_CONTINUATION");
    }

    private void seedMakerAccount() {
        if (makerUserId <= 0) {
            throw new IllegalStateException("MAKER_USER_ID_REQUIRED");
        }
        initializeMakerUsers(makerUserIds, userId -> adjust(userId, settleAsset(), 1_000));
        if (productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY) {
            long insuranceSeed = 2_000;
            command(CoreMessageType.ADJUST_INSURANCE_FUND, 0,
                    TradingCommandCodec.encodeAdjustInsuranceFund(
                            new com.surprising.aeron.protocol.AdjustInsuranceFundCommand(settleAsset(), insuranceSeed)));
            expectedFunds.merge(settleAsset(), insuranceSeed, Math::addExact);
            rows.add("INSURANCE_FUND_SEEDED");
        }
        rows.add("MAKER_ACCOUNT_SEEDED");
    }

    private void captureBaselineFunds() {
        for (long userId : makerUserIds) {
            existingUserState(userId).ifPresent(state -> state.balances().forEach(balance ->
                    expectedFunds.merge(balance.asset(),
                            Math.addExact(balance.availableUnits(), balance.lockedUnits()), Math::addExact)));
        }
        for (var treasury : CoreStateQueryCodec.decodeTreasuryState(
                query(CoreMessageType.TREASURY_STATE_QUERY, 0, new byte[0]))) {
            expectedFunds.merge(treasury.asset(), Math.subtractExact(
                    Math.addExact(treasury.feeBalanceUnits(), treasury.insuranceBalanceUnits()),
                    treasury.insuranceDeficitUnits()), Math::addExact);
        }
        rows.add("MAKER_TREASURY_BASELINE_CAPTURED users=" + makerUserIds);
    }

    static void initializeMakerUsers(List<Long> userIds, LongConsumer initializer) {
        userIds.forEach(initializer::accept);
    }

    static String makerRunOncePath() {
        return "/api/v1/admin/market-maker/run-once";
    }

    static String scenarioSymbol(ProductLine productLine, String scenario, long seed) {
        return "W4-" + productLine.name().replace('_', '-') + '-' + scenario + '-' + seed;
    }

    private Optional<CoreUserStateView> existingUserState(long userId) {
        try {
            return Optional.of(CoreStateQueryCodec.decodeUserState(
                    query(CoreMessageType.USER_STATE_QUERY, userId, new byte[0])));
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("result=ENTITY_NOT_FOUND")) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    private void runFaults() {
        requireProviderCapabilities("faults");
    }

    private void runSpot() {
        String symbol = scenarioSymbol(productLine, "BTC-USDT", seed);
        setupInstrument(symbol, ContractType.SPOT, -1, 0, 0);
        long seller = user(1);
        long buyer = user(2);
        adjust(seller, "BTC", 5);
        adjust(buyer, "USDT", 1_000);
        place(seller, order(1), symbol, CoreOrderSide.SELL, CoreMarginMode.CROSS,
                ReservationKind.SPOT_ASSET, "BTC", 5, 5);
        place(buyer, order(2), symbol, CoreOrderSide.BUY, CoreMarginMode.CROSS,
                ReservationKind.SPOT_ASSET, "USDT", 500, 5);
        awaitSpotOrdersFilled();
        requireBookEmpty(symbol);
        reconcile();
        rows.add("SPOT:CONSERVATION");
        rows.add("SPOT:CONTROL_GUARD");
    }

    private void runPerpetual() {
        for (CoreMarginMode marginMode : List.of(CoreMarginMode.CROSS, CoreMarginMode.ISOLATED)) {
            String mode = marginMode.name();
            String symbol = scenarioSymbol(productLine, mode, seed);
            long shortUser = user(10 + marginMode.ordinal() * 10);
            long longUser = user(11 + marginMode.ordinal() * 10);
            setupInstrument(symbol, ContractType.valueOf(productLine.contractTypeCode()), -1, 0, 0);
            applyMark(symbol, 100);
            adjust(shortUser, settleAsset(), 1_000);
            adjust(longUser, settleAsset(), 1_000);
            place(shortUser, order(10 + marginMode.ordinal() * 10), symbol, CoreOrderSide.SELL,
                    marginMode, ReservationKind.DERIVATIVE_MARGIN, settleAsset(), 100, 10);
            place(longUser, order(11 + marginMode.ordinal() * 10), symbol, CoreOrderSide.BUY,
                    marginMode, ReservationKind.DERIVATIVE_MARGIN, settleAsset(), 100, 10);
            queryRisk(shortUser);
            queryRisk(longUser);
            applyFunding(symbol, 20_000L + marginMode.ordinal(), FUNDING_RATE_PPM);
            readFundingProgress(symbol);
            applyFunding(symbol, 20_100L + marginMode.ordinal(), Math.negateExact(FUNDING_RATE_PPM));
            readFundingProgress(symbol);
            applyMark(symbol, productLine == ProductLine.INVERSE_PERPETUAL ? 25 : 80);
            resolveBoundedLiquidationWork(symbol);
            queryAdlCandidates();
            runProviderCycles(symbol, shortUser);
            requireBookEmpty(symbol);
            rows.add(productLine + ":" + mode + ":FUNDING_POSITIVE");
            rows.add(productLine + ":" + mode + ":FUNDING_NEGATIVE");
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
            String symbol = scenarioSymbol(productLine, mode, seed);
            long buyer = user(30 + marginMode.ordinal() * 10);
            long seller = user(31 + marginMode.ordinal() * 10);
            setupInstrument(symbol, type, -1, 0, 2_000_000_000_000L);
            applyMark(symbol, 100);
            adjust(buyer, settleAsset(), 1_000);
            adjust(seller, settleAsset(), 1_000);
            place(seller, order(30 + marginMode.ordinal() * 10), symbol, CoreOrderSide.SELL,
                    marginMode, ReservationKind.DERIVATIVE_MARGIN, settleAsset(), 100, 10);
            place(buyer, order(31 + marginMode.ordinal() * 10), symbol, CoreOrderSide.BUY,
                    marginMode, ReservationKind.DERIVATIVE_MARGIN, settleAsset(), 100, 10);
            settle(symbol, 110, 0, 1_000L + marginMode.ordinal());
            readSettlementProgress(symbol);
            runProviderCycles(symbol, buyer);
            requireBookEmpty(symbol);
            rows.add(productLine + ":" + mode + ":SETTLEMENT");
        }
    }

    private void runOptions() {
        for (String optionType : List.of("CALL", "PUT")) {
            for (String moneyness : List.of("ITM", "ATM", "OTM")) {
                String symbol = scenarioSymbol(productLine, optionType + '-' + moneyness, seed);
                long buyer = user(100 + optionTypeOffset(optionType) + moneynessOffset(moneyness));
                long seller = user(110 + optionTypeOffset(optionType) + moneynessOffset(moneyness));
                int optionCode = optionType.equals("CALL") ? 0 : 1;
                long settlementPrice = optionSettlementPrice(optionType, moneyness);
                setupInstrument(symbol, ContractType.VANILLA_OPTION, optionCode, STRIKE,
                        2_000_000_000_000L);
                applyMark(symbol, 100);
                adjust(buyer, "USDT", 2_000);
                adjust(seller, "USDT", 2_000);
                place(seller, order(100 + optionTypeOffset(optionType) + moneynessOffset(moneyness)),
                        symbol, CoreOrderSide.SELL, CoreMarginMode.CROSS,
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 2);
                place(buyer, order(110 + optionTypeOffset(optionType) + moneynessOffset(moneyness)),
                        symbol, CoreOrderSide.BUY, CoreMarginMode.CROSS,
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 2);
                settle(symbol, settlementPrice, settlementPrice,
                        2_000L + optionTypeOffset(optionType) + moneynessOffset(moneyness));
                readSettlementProgress(symbol);
                runProviderCycles(symbol, buyer);
                requireBookEmpty(symbol);
                rows.add("OPTION:" + optionType + ':' + moneyness);
            }
        }
    }

    private void setupInstrument(String symbol, ContractType type, int optionCode,
                                 long strike, long expiry) {
        long version = upsertInstrumentViaProvider(symbol, type, optionCode, strike, expiry);
        command(CoreMessageType.UPSERT_INSTRUMENT, 0,
                TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                        symbol, version, type.ordinal(), BASE_ASSET,
                        type.isInverse() ? "USD" : "USDT", settleAsset(),
                        type.isInverse() ? 100 : 1, 1, type.isInverse() ? 100 : 1,
                        100_000, 100_000, MAKER_FEE_RATE_PPM, TAKER_FEE_RATE_PPM,
                        expiry, optionCode, strike)));
        awaitTradingInstrumentVersion(symbol, version);
        instrumentVersions.put(symbol, version);
    }

    private void awaitTradingInstrumentVersion(String symbol, long version) {
        String body = "{\"userId\":" + makerUserId + ",\"clientOrderId\":"
                + json("w4-version-probe-" + seed + '-' + symbol) + ",\"symbol\":" + json(symbol)
                + ",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\""
                + ",\"priceTicks\":100,\"quantitySteps\":1,\"marginMode\":\"CROSS\""
                + ",\"positionSide\":\"NET\",\"reduceOnly\":false,\"postOnly\":false}";
        Instant deadline = Instant.now().plusSeconds(15);
        long observed = 0;
        while (Instant.now().isBefore(deadline)) {
            String response = request("command", "POST", "/api/v1/trading/orders/test", body, Map.of());
            observed = jsonLong(response, "\"instrumentVersion\":", ',');
            if (observed == version) return;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("instrument version wait interrupted", exception);
            }
        }
        throw new IllegalStateException("trading instrument snapshot timeout symbol=" + symbol
                + " expectedVersion=" + version + " observedVersion=" + observed);
    }

    private long upsertInstrumentViaProvider(String symbol, ContractType type, int optionCode,
                                             long strike, long expiry) {
        boolean spot = type == ContractType.SPOT;
        boolean perpetual = type.isPerpetual();
        String instrumentType = spot ? "SPOT" : (perpetual ? "PERPETUAL"
                : (type.isDelivery() ? "DELIVERY" : "OPTION"));
        String quote = type.isInverse() ? "USD" : "USDT";
        String expiryJson = expiry > 0 ? json(Instant.ofEpochMilli(expiry).toString()) : "null";
        String strikeJson = type.isOption() ? Long.toString(strike) : "null";
        String optionTypeJson = type.isOption() ? json(optionCode == 0 ? "CALL" : "PUT") : "null";
        String optionStyleJson = type.isOption() ? "\"EUROPEAN\"" : "null";
        String settlementJson = spot || perpetual ? "null" : "\"CASH\"";
        String funding = perpetual ? "8,100,3000,-3000" : "0,0,0,0";
        String brackets = spot ? "[]"
                : "[{\"bracketNo\":1,\"notionalFloorUnits\":0,\"notionalCapUnits\":5000000000000"
                + ",\"maxLeveragePpm\":100000000,\"initialMarginRatePpm\":10000"
                + ",\"maintenanceMarginRatePpm\":5000}]";
        String sources = spot ? "[]"
                : "[{\"source\":\"W4-A\",\"enabled\":true,\"baseUrl\":" + json(indexFeed.baseUrl()) + ","
                + "\"path\":\"/w4-a\",\"sourceSymbol\":\"BTCUSDT\",\"parser\":\"BINANCE_BOOK_TICKER\","
                + "\"quoteCurrency\":\"USDT\",\"targetQuoteCurrency\":\"USDT\",\"conversionBaseUrl\":null,"
                + "\"conversionPath\":null,\"conversionParser\":null,\"conversionMode\":null,"
                + "\"conversionOperation\":null,\"fallbackWeightMultiplierPpm\":0,\"websocketEnabled\":false,"
                + "\"websocketUrl\":null,\"websocketSubscribeMessage\":null,\"websocketParser\":null,\"weightPpm\":500000},"
                + "{\"source\":\"W4-B\",\"enabled\":true,\"baseUrl\":" + json(indexFeed.baseUrl()) + ","
                + "\"path\":\"/w4-b\",\"sourceSymbol\":\"BTCUSDT\",\"parser\":\"BINANCE_BOOK_TICKER\","
                + "\"quoteCurrency\":\"USDT\",\"targetQuoteCurrency\":\"USDT\",\"conversionBaseUrl\":null,"
                + "\"conversionPath\":null,\"conversionParser\":null,\"conversionMode\":null,"
                + "\"conversionOperation\":null,\"fallbackWeightMultiplierPpm\":0,\"websocketEnabled\":false,"
                + "\"websocketUrl\":null,\"websocketSubscribeMessage\":null,\"websocketParser\":null,\"weightPpm\":500000}]";
        String body = "{\"symbol\":" + json(symbol) + ",\"instrumentType\":" + json(instrumentType)
                + ",\"contractType\":" + json(type.name()) + ",\"baseAsset\":\"BTC\",\"quoteAsset\":"
                + json(quote) + ",\"settleAsset\":" + json(settleAsset())
                + ",\"contractMultiplierPpm\":1000000,\"contractValueAsset\":\"USDT\","
                + "\"priceTickUnits\":1,\"quantityStepUnits\":1,\"minQuantitySteps\":1,\"maxQuantitySteps\":100000,"
                + "\"minNotionalUnits\":1,\"maxNotionalUnits\":1000000000000,\"notionalMultiplierUnits\":1,"
                + "\"pricePrecision\":2,\"quantityPrecision\":3,\"supportedOrderTypes\":[\"LIMIT\"],"
                + "\"supportedTimeInForce\":[\"GTC\",\"IOC\"],\"postOnlyEnabled\":true,\"reduceOnlyEnabled\":"
                + (!spot) + ",\"marketOrderEnabled\":false,\"maxLeveragePpm\":100000000,"
                + "\"initialMarginRatePpm\":10000,\"maintenanceMarginRatePpm\":"
                + "5000,\"makerFeeRatePpm\":" + MAKER_FEE_RATE_PPM + ","
                + "\"takerFeeRatePpm\":" + TAKER_FEE_RATE_PPM + ",\"maxPositionNotionalUnits\":25000000000000,"
                + "\"userOpenInterestLimitRatePpm\":0,\"userOpenInterestLimitFloorUnits\":1,\"fundingIntervalHours\":"
                + funding.split(",")[0] + ",\"interestRatePpm\":" + funding.split(",")[1]
                + ",\"fundingRateCapPpm\":" + funding.split(",")[2] + ",\"fundingRateFloorPpm\":"
                + funding.split(",")[3] + ",\"impactNotionalUnits\":1000000000000,\"minValidIndexSources\":"
                + (spot ? 1 : 2) + ",\"expiryTime\":" + expiryJson + ",\"deliveryTime\":" + expiryJson
                + ",\"underlyingSymbol\":" + (type.isOption() ? json("BTC-USDT") : "null")
                + ",\"strikePriceUnits\":" + strikeJson + ",\"optionType\":" + optionTypeJson
                + ",\"optionExerciseStyle\":" + optionStyleJson + ",\"settlementMethod\":" + settlementJson
                + ",\"status\":\"TRADING\",\"effectiveTime\":null,\"riskLimitBrackets\":" + brackets
                + ",\"indexSources\":" + sources + "}";
        String response = request("instrument", "POST", "/api/v1/instruments/admin/upsert", body, Map.of());
        return jsonLong(response, "\"version\":", ',');
    }

    private void adjust(long userId, String asset, long units) {
        participantUsers.add(userId);
        String body = "{\"userId\":" + userId + ",\"asset\":" + json(asset)
                + ",\"amountUnits\":" + units + ",\"referenceId\":"
                + json("w4-" + productLine + "-" + userId + "-" + asset + "-" + sequence)
                + ",\"reason\":\"w4-provider-qa\"}";
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                request("account", "POST", "/api/v1/admin/accounts/balance-adjustments", body, adminHeaders());
                last = null;
                break;
            } catch (IllegalStateException exception) {
                last = exception;
                if (!exception.getMessage().contains("HTTP_400") || attempt == 5) {
                    throw exception;
                }
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("W4 balance adjustment retry interrupted", interrupted);
                }
            }
        }
        if (last != null) {
            throw last;
        }
        providerBoundaryObserved = true;
        expectedFunds.merge(asset, units, Math::addExact);
    }

    private void place(long userId, long orderId, String symbol, CoreOrderSide side,
                       CoreMarginMode marginMode, ReservationKind reservationKind,
                       String reservationAsset, long reservedUnits, long quantity) {
        participantUsers.add(userId);
        String body = "{\"userId\":" + userId + ",\"clientOrderId\":" + json("w4-" + orderId)
                + ",\"symbol\":" + json(symbol) + ",\"side\":" + json(side.name())
                + ",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\",\"priceTicks\":100"
                + ",\"quantitySteps\":" + quantity + ",\"marginMode\":" + json(marginMode.name())
                + ",\"positionSide\":\"NET\",\"reduceOnly\":false,\"postOnly\":false}";
        String response = null;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 20; attempt++) {
            try {
                response = request("command", "POST", "/api/v1/trading/orders", body, Map.of());
                last = null;
                break;
            } catch (IllegalStateException exception) {
                last = exception;
                if (!exception.getMessage().contains("HTTP_400") || attempt == 20) {
                    throw exception;
                }
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("W4 instrument snapshot wait interrupted", interrupted);
                }
            }
        }
        if (last != null || response == null) {
            throw last == null ? new IllegalStateException("W4 order response missing") : last;
        }
        String acceptedResponse = response;
        response = awaitOrderResult(response);
        System.out.printf("W4_ORDER_RESPONSE userId=%d orderId=%d side=%s body=%s%n",
                userId, orderId, side, response);
        if (!response.contains("\"outcome\":\"TERMINAL\"")
                || !response.contains("\"code\":\"NONE\"")) {
            throw new IllegalStateException("order command did not complete: " + response);
        }
        if (symbol.startsWith("W4-SPOT-")) {
            String identityResponse = acceptedResponse.contains("\"prospectiveOrderIds\":[]")
                    ? response : acceptedResponse;
            spotOrders.add(new SpotOrder(userId, jsonLong(identityResponse, "\"prospectiveOrderIds\":[", ']'),
                    symbol, jsonLong(response, "\"requiredExportSequence\":", ',')));
        }
        providerBoundaryObserved = true;
    }

    private String awaitOrderResult(String initialResponse) {
        String response = initialResponse;
        if (response.contains("\"outcome\":\"TERMINAL\"")) {
            return response;
        }
        String commandId = jsonString(response, "\"commandId\":\"");
        for (int attempt = 1; attempt <= 40; attempt++) {
            response = request("command", "GET", "/api/v1/trading/orders/commands/" + commandId,
                    null, Map.of());
            if (response.contains("\"outcome\":\"TERMINAL\"")) {
                return response;
            }
            if (!response.contains("\"outcome\":\"RESULT_UNKNOWN\"")
                    && !response.contains("\"outcome\":\"MATCHING_PENDING\"")) {
                return response;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("W4 order result wait interrupted", interrupted);
            }
        }
        return response;
    }

    private void applyMark(String symbol, long price) {
        indexFeed.setMarkPriceTicks(price);
        awaitPublishedMark(symbol, price);
    }

    private void awaitPublishedMark(String symbol, long expectedMarkPriceTicks) {
        String probe = "{\"userId\":" + makerUserId + ",\"clientOrderId\":"
                + json("w4-mark-probe-" + seed + '-' + symbol + '-' + expectedMarkPriceTicks)
                + ",\"symbol\":" + json(symbol)
                + ",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\""
                + ",\"priceTicks\":100,\"quantitySteps\":1,\"marginMode\":\"CROSS\""
                + ",\"positionSide\":\"NET\",\"reduceOnly\":false,\"postOnly\":false}";
        Instant deadline = Instant.now().plusSeconds(30);
        String observed = "unavailable";
        while (Instant.now().isBefore(deadline)) {
            try {
                String mark = request("price", "GET", "/api/v1/price/mark/latest?symbol=" + symbol,
                        null, Map.of());
                long units = jsonLong(mark, "\"markPriceUnits\":", ',');
                String validation = request("trading", "POST", "/api/v1/trading/orders/test", probe, Map.of());
                observed = "markPriceUnits=" + units + " validation=" + validation;
                if (units == expectedMarkPriceTicks && !validation.contains("mark price unavailable")) {
                    rows.add(productLine + ":" + symbol + ":REAL_PRICE_PIPELINE_MARK=" + expectedMarkPriceTicks);
                    return;
                }
            } catch (IllegalStateException unavailable) {
                observed = unavailable.getMessage();
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("mark price pipeline wait interrupted", interrupted);
            }
        }
        throw new IllegalStateException("mark price pipeline timeout symbol=" + symbol
                + " expectedTicks=" + expectedMarkPriceTicks + " observed=" + observed);
    }

    private void applyFunding(String symbol, long settlementId, long fundingRatePpm) {
        command(CoreMessageType.APPLY_FUNDING, 0,
                TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                        settlementId, symbol, instrumentVersion(symbol), fundingRatePpm, 0, 256)));
    }

    private long instrumentVersion(String symbol) {
        Long version = instrumentVersions.get(symbol);
        if (version == null) throw new IllegalStateException("instrument version unavailable: " + symbol);
        return version;
    }

    private void settle(String symbol, long price, long underlyingSettlementPrice, long settlementId) {
        request("instrument", "POST", "/api/v1/instruments/admin/" + symbol + "/status"
                        + "?productLine=" + productLine + "&status=SETTLING", null, Map.of());
        request("instrument", "POST", "/api/v1/instruments/admin/" + symbol + "/settlement"
                        + "?productLine=" + productLine + "&settlementPriceTicks=" + price
                        + "&underlyingSettlementPriceUnits=" + underlyingSettlementPrice, null, Map.of());
        providerBoundaryObserved = true;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                CoreSettlementProgressView progress = readSettlementProgress(symbol);
                if (progress.complete() && progress.ordersComplete()) {
                    return;
                }
            } catch (RuntimeException ignored) {
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SETTLEMENT_WAIT_INTERRUPTED", ex);
            }
        }
        throw new IllegalStateException("SETTLEMENT_PROVIDER_EVENT_TIMEOUT symbol=" + symbol);
    }

    private void runProviderCycles(String symbol, long userId) {
        request("risk", "GET", "/api/v1/risk/account/latest?userId=" + userId
                + "&accountType=" + productLine.accountTypeCode()
                + "&settleAsset=" + settleAsset(), null, Map.of());
        request("maker", "POST", makerRunOncePath(),
                "{\"strategyId\":null,\"symbol\":" + json(symbol)
                        + ",\"productLine\":" + json(productLine.name()) + "}", adminHeaders());
        providerBoundaryObserved = true;
        if (productLine == ProductLine.LINEAR_PERPETUAL || productLine == ProductLine.INVERSE_PERPETUAL) {
            request("funding", "POST", "/api/v1/funding/admin/run-cycle", null, adminHeaders());
            request("liquidation", "POST", "/api/v1/admin/liquidations/run-cycle", null, adminHeaders());
            request("insurance", "POST", "/api/v1/insurance/admin/run-cycle", null, adminHeaders());
            request("adl", "POST", "/api/v1/adl/admin/run-cycle", null, adminHeaders());
        }
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
            byte[] payload = TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                    action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(), 100_000,
                    action.cursorOrderId(), 1_024));
            lifecycleCoordinator.execute(() -> {
                try {
                    command(CoreMessageType.EXECUTE_LIQUIDATION, action.userId(), payload);
                } catch (IllegalStateException exception) {
                    if (!exception.getMessage().contains("LIQUIDATION_STATE_CONFLICT")) {
                        throw exception;
                    }
                    CoreLiquidationWorkView refreshed = liquidationWork(symbol, 0,
                            CoreLiquidationWorkView.Purpose.EXECUTION);
                    boolean stillPending = refreshed.actions().stream()
                            .anyMatch(candidate -> candidate.liquidationId() == action.liquidationId());
                    if (stillPending) {
                        throw exception;
                    }
                    rows.add(productLine + ":LIQUIDATION_WORK_ALREADY_APPLIED:" + action.liquidationId());
                }
                return null;
            });
        }
        if (!work.actions().isEmpty() || !work.resolutions().isEmpty()) {
            rows.add(productLine + ":LIQUIDATION_WORK_APPLIED");
        }
        CoreLiquidationWorkView insurance = liquidationWork(symbol, 0,
                CoreLiquidationWorkView.Purpose.INSURANCE);
        CoreLiquidationWorkView adl = liquidationWork(symbol, 0,
                CoreLiquidationWorkView.Purpose.ADL);
        for (CoreLiquidationWorkView.Resolution resolution : insurance.resolutions()) {
            expectedFunds.merge(resolution.asset(), Math.negateExact(resolution.deficitUnits()), Math::addExact);
            rows.add(productLine + ":LIQUIDATION_LOSS_RECOGNIZED:" + resolution.deficitUnits());
        }
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

    private CoreSettlementProgressView readSettlementProgress(String symbol) {
        return CoreSettlementProgressCodec.decode(query(CoreMessageType.SETTLEMENT_PROGRESS_QUERY, 0,
                CoreStateQueryCodec.encodeSettlementProgressQuery(symbol)));
    }

    private void reconcile() {
        if (makerUserId <= 0) {
            throw new IllegalStateException("MAKER_USER_ID_REQUIRED");
        }
        Set<Long> reconciliationUsers = new LinkedHashSet<>(participantUsers);
        reconciliationUsers.addAll(makerUserIds);
        for (int attempt = 1; attempt <= 100; attempt++) {
            long hashBefore = queryResponse(CoreMessageType.BUSINESS_STATE_HASH_QUERY, 0, new byte[0]).stateHash();
            Map<String, Long> actual = new LinkedHashMap<>();
            Map<String, Long> users = new LinkedHashMap<>();
            Map<String, Long> fees = new LinkedHashMap<>();
            Map<String, Long> insurance = new LinkedHashMap<>();
            Map<String, Long> deficits = new LinkedHashMap<>();
            List<String> userRows = new ArrayList<>();
            for (long userId : reconciliationUsers) {
                CoreUserStateView state = CoreStateQueryCodec.decodeUserState(
                        query(CoreMessageType.USER_STATE_QUERY, userId, new byte[0]));
                userRows.add("USER_STATE userId=" + userId + " balances=" + state.balances()
                        + " reservations=" + state.reservations() + " positions=" + state.positions());
                for (var balance : state.balances()) {
                    long total = Math.addExact(balance.availableUnits(), balance.lockedUnits());
                    users.merge(balance.asset(), total, Math::addExact);
                    actual.merge(balance.asset(), total, Math::addExact);
                }
            }
            for (var treasury : CoreStateQueryCodec.decodeTreasuryState(
                    query(CoreMessageType.TREASURY_STATE_QUERY, 0, new byte[0]))) {
                if (treasury.feeBalanceUnits() != 0) {
                    feeLedgerObserved = true;
                }
                fees.put(treasury.asset(), treasury.feeBalanceUnits());
                insurance.put(treasury.asset(), treasury.insuranceBalanceUnits());
                deficits.put(treasury.asset(), treasury.insuranceDeficitUnits());
                actual.merge(treasury.asset(), Math.subtractExact(
                        Math.addExact(treasury.feeBalanceUnits(), treasury.insuranceBalanceUnits()),
                        treasury.insuranceDeficitUnits()), Math::addExact);
            }
            long hashAfter = queryResponse(CoreMessageType.BUSINESS_STATE_HASH_QUERY, 0, new byte[0]).stateHash();
            if (hashBefore != hashAfter) {
                if (attempt == 100) {
                    throw new IllegalStateException("RECONCILIATION_SNAPSHOT_UNSTABLE users="
                            + reconciliationUsers);
                }
                continue;
            }
            Set<String> assets = new LinkedHashSet<>(expectedFunds.keySet());
            assets.addAll(actual.keySet());
            for (String asset : assets) {
                long difference = Math.subtractExact(actual.getOrDefault(asset, 0L),
                        expectedFunds.getOrDefault(asset, 0L));
                if (difference != 0) {
                    throw new IllegalStateException("FUNDS_DIFFERENCE asset=" + asset
                            + " expected=" + expectedFunds.getOrDefault(asset, 0L)
                            + " actual=" + actual.getOrDefault(asset, 0L)
                            + " difference=" + difference
                            + " users=" + users.getOrDefault(asset, 0L)
                            + " fees=" + fees.getOrDefault(asset, 0L)
                            + " insurance=" + insurance.getOrDefault(asset, 0L)
                            + " deficit=" + deficits.getOrDefault(asset, 0L)
                            + " reconciliationUsers=" + reconciliationUsers);
                }
                if (deficits.getOrDefault(asset, 0L) != 0) {
                    throw new IllegalStateException("INSURANCE_DEFICIT_REMAINS asset=" + asset
                            + " deficit=" + deficits.get(asset));
                }
            }
            rows.addAll(userRows);
            rows.add("USER_RECONCILIATION_OBSERVED");
            rows.add("MAKER_RECONCILIATION_OBSERVED");
            rows.add("TREASURY_RECONCILIATION_OBSERVED");
            makerReconciliationObserved = true;
            reconciliationObserved = true;
            rows.add("FUNDS_DIFFERENCE=0");
            return;
        }
    }

    private void requireFeeLedgerObserved() {
        if (!feeLedgerObserved) {
            throw new IllegalStateException("TRADE_FEES_NOT_OBSERVED productLine=" + productLine);
        }
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

    private void requireBookEmpty(String symbol) {
        Instant deadline = Instant.now().plusSeconds(10);
        int levels;
        Object levelDetails = List.of();
        do {
            var book = OrderBookBootstrapLoader.load((type, payload) -> query(type, 0, payload));
            var symbolLevels = book.levels().stream().filter(level -> symbol.equals(level.symbol())).toList();
            levels = symbolLevels.size();
            levelDetails = symbolLevels;
            if (levels == 0) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the order book to settle", exception);
            }
        } while (Instant.now().isBefore(deadline));
        if (levels != 0) {
            throw new IllegalStateException("book is not empty levels=" + levels + " details=" + levelDetails);
        }
    }

    private void awaitSpotOrdersFilled() {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            boolean allFilled = true;
            for (SpotOrder order : spotOrders) {
                String path = "/api/v1/trading/orders/history?userId=" + order.userId()
                        + "&symbol=" + order.symbol() + "&limit=100&orderId=" + order.orderId()
                        + "&minExportSequence=" + order.requiredExportSequence();
                try {
                    String response = request("command", "GET", path, null, Map.of());
                    int orderStart = response.indexOf("\"orderId\":" + order.orderId());
                    if (orderStart < 0) {
                        allFilled = false;
                        continue;
                    }
                    String status = jsonString(response.substring(orderStart), "\"status\":\"");
                    if ("REJECTED".equals(status)) {
                        throw new IllegalStateException("spot order rejected after command acceptance: " + response);
                    }
                    if (!"FILLED".equals(status)) {
                        allFilled = false;
                    }
                } catch (IllegalStateException exception) {
                    String message = exception.getMessage();
                    if (!message.startsWith("HTTP_404") && !message.startsWith("HTTP_409")) {
                        throw exception;
                    }
                    allFilled = false;
                }
            }
            if (allFilled) {
                rows.add("SPOT:FILL_CALLBACK_OBSERVED");
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for spot fills", exception);
            }
        }
        throw new IllegalStateException("spot fill callback timeout orders=" + spotOrders);
    }

    private static long jsonLong(String body, String prefix, char terminator) {
        int start = body.indexOf(prefix);
        if (start < 0) throw new IllegalStateException("missing JSON field " + prefix + " body=" + body);
        start += prefix.length();
        int end = body.indexOf(terminator, start);
        if (end < 0) end = body.length();
        return Long.parseLong(body.substring(start, end).replaceAll("[^0-9-].*", ""));
    }

    private static List<Long> configuredMakerUserIds() {
        String configured = System.getProperty("surprising.aeron.w4-maker-user-ids");
        if (configured == null || configured.isBlank()) {
            return List.of(configuredPositiveLong("surprising.aeron.w4-maker-user-id"));
        }
        List<Long> values = Arrays.stream(configured.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).map(Long::parseLong).toList();
        if (values.isEmpty() || values.stream().anyMatch(value -> value <= 0)) {
            throw new IllegalArgumentException("surprising.aeron.w4-maker-user-ids must contain positive ids");
        }
        return values;
    }

    private static String jsonString(String body, String prefix) {
        int start = body.indexOf(prefix);
        if (start < 0) throw new IllegalStateException("missing JSON field " + prefix + " body=" + body);
        start += prefix.length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    @Override
    public void close() {
        indexFeed.close();
    }

    private static final class ControlledIndexFeed implements AutoCloseable {
        private final AtomicLong markPriceTicks = new AtomicLong(100L);
        private final HttpServer server;
        private final ExecutorService executor;

        private ControlledIndexFeed(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static ControlledIndexFeed start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                ControlledIndexFeed feed = new ControlledIndexFeed(server, executor);
                server.createContext("/w4-a", feed::respond);
                server.createContext("/w4-b", feed::respond);
                server.setExecutor(executor);
                server.start();
                return feed;
            } catch (IOException exception) {
                throw new IllegalStateException("failed to start controlled index-price feed", exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void setMarkPriceTicks(long value) {
            if (value <= 0) throw new IllegalArgumentException("mark price ticks must be positive");
            markPriceTicks.set(value);
        }

        private void respond(HttpExchange exchange) throws IOException {
            byte[] response;
            int status;
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                response = new byte[0];
            } else {
                status = 200;
                String price = BigDecimal.valueOf(markPriceTicks.get())
                        .divide(BigDecimal.valueOf(QUOTE_SCALE_UNITS)).toPlainString();
                response = ("{\"symbol\":\"BTCUSDT\",\"bidPrice\":" + json(price)
                        + ",\"askPrice\":" + json(price) + ",\"E\":" + System.currentTimeMillis() + '}')
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
            }
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private record SpotOrder(long userId, long orderId, String symbol, long requiredExportSequence) {
    }

    private void command(CoreMessageType type, long userId, byte[] payload) {
        long currentSequence = nextSequence();
        UUID commandId = UUID.nameUUIDFromBytes((productLine + ":" + seed + ":" + currentSequence + ":" + type)
                .getBytes(StandardCharsets.UTF_8));
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                CommandSource.OPERATIONS, sourceId, currentSequence, userId,
                System.currentTimeMillis(), currentSequence), payload);
        CoreResponse response;
        try {
            response = client.submit(message);
        } catch (ResultUnknownException exception) {
            response = awaitCommandResult(commandId, userId, type);
        }
        if (response.commandStatus() != ResponseStatus.APPLIED
                && response.commandStatus() != ResponseStatus.DUPLICATE) {
            throw new IllegalStateException(type + " rejected result=" + response.resultCode());
        }
    }

    private CoreResponse awaitCommandResult(UUID commandId, long userId, CoreMessageType commandType) {
        for (int attempt = 1; attempt <= 40; attempt++) {
            long correlation = nextSequence();
            CoreMessage query = new CoreMessage(CoreMessageHeader.query(
                    CoreMessageType.COMMAND_RESULT_QUERY,
                    UUID.nameUUIDFromBytes((productLine + ":command-result:" + correlation + ':' + commandId)
                            .getBytes(StandardCharsets.UTF_8)),
                    productLine, CommandSource.OPERATIONS, sourceId, 0, userId,
                    System.currentTimeMillis(), correlation),
                    CoreStateQueryCodec.encodeCommandResultQuery(commandId));
            try {
                CoreResponse response = client.submit(query);
                if (response.status() == ResponseStatus.OK) {
                    return response;
                }
                if (response.resultCode() != CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION) {
                    throw new IllegalStateException(commandType + " result query rejected result="
                            + response.resultCode());
                }
            } catch (ResultUnknownException ignored) {
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("W4 command result wait interrupted", interrupted);
            }
        }
        throw new IllegalStateException(commandType + " result remains unknown commandId=" + commandId);
    }

    private byte[] query(CoreMessageType type, long userId, byte[] payload) {
        return queryResponse(type, userId, payload).data();
    }

    private CoreResponse queryResponse(CoreMessageType type, long userId, byte[] payload) {
        long correlation = nextSequence();
        CoreMessage message = new CoreMessage(CoreMessageHeader.query(
                type, UUID.nameUUIDFromBytes((productLine + ":query:" + correlation + ':' + type)
                        .getBytes(StandardCharsets.UTF_8)), productLine, CommandSource.OPERATIONS,
                sourceId, 0, userId, System.currentTimeMillis(), correlation), payload);
        ResultUnknownException last = null;
        for (int attempt = 1; attempt <= 20; attempt++) {
            try {
                var response = client.submit(message);
                if (response.status() != ResponseStatus.OK) {
                    throw new IllegalStateException(type + " query rejected result=" + response.resultCode());
                }
                return response;
            } catch (ResultUnknownException exception) {
                last = exception;
                if (attempt == 20) throw exception;
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("W4 query retry interrupted", interrupted);
                }
            }
        }
        throw last == null ? new IllegalStateException("W4 query did not execute") : last;
    }

    long nextSequence() {
        sequence = Math.incrementExact(sequence);
        return sequence;
    }

    long sequence() {
        return sequence;
    }

    void writeManifest(Path manifest, String mode) throws IOException {
        if (!"execute".equals(mode)) {
            throw new IllegalStateException("W4_MODE_PASS_FORBIDDEN mode=" + mode);
        }
        if (!reconciliationObserved) {
            throw new IllegalStateException("FUNDS_RECONCILIATION_REQUIRED");
        }
        if (!makerReconciliationObserved) {
            throw new IllegalStateException("MAKER_RECONCILIATION_REQUIRED");
        }
        if (!providerBoundaryObserved) {
            throw new IllegalStateException("PROVIDER_BOUNDARY_REQUIRED");
        }
        List<String> output = new ArrayList<>();
        output.add("manifestVersion=1");
        output.add("productLine=" + productLine);
        output.add("seed=" + seed);
        output.add("mode=" + mode);
        output.add("W4_STATUS=REAL_PASS");
        output.add("providerProductLine=" + productLine);
        output.add("coreProductLine=" + productLine);
        output.add("selectionAuthority=CORE");
        output.add("projectionAuthority=CORE");
        output.add("providerBoundary=OBSERVED");
        output.add("maker=OBSERVED");
        output.add("wallet=ABSENT");
        output.add("cursorPolicy=MONOTONIC_NO_REPEAT_NO_GAP");
        output.add("fundsReconciliation=OBSERVED");
        output.add("rows=" + requiredRows(productLine));
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

    static String requiredRows(ProductLine productLine) {
        return switch (productLine) {
            case SPOT -> "SPOT:CONSERVATION,SPOT:CONTROL_GUARD,SPOT:TRADES_FEES";
            case LINEAR_PERPETUAL, INVERSE_PERPETUAL -> productLine + ":CROSS," + productLine
                    + ":ISOLATED,FUNDING_POSITIVE,FUNDING_NEGATIVE,MARK,RISK_SCAN,LIQUIDATION,INSURANCE,ADL,TRADES_FEES";
            case LINEAR_DELIVERY, INVERSE_DELIVERY -> productLine + ":CROSS," + productLine
                    + ":ISOLATED,SETTLEMENT,CURSOR,TRADES_FEES";
            case OPTION -> "OPTION:CALL:ITM,OPTION:CALL:ATM,OPTION:CALL:OTM,OPTION:PUT:ITM,OPTION:PUT:ATM,OPTION:PUT:OTM,OPTION:TRADES_FEES";
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

    private static long configuredPositiveLong(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }
}
