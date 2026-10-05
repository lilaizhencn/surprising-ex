package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.asset.repository.AssetRepository;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.repository.*;
import com.surprising.instrument.provider.service.InstrumentStorageService;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.surprising.gateway.provider.auth.PasswordHasher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicitly opt-in; run once against each isolated product-line runtime, never production. */
@EnabledIfEnvironmentVariable(named = "SIX_LINE_LIVE_PRODUCT", matches = ".+")
class SixProductLineContractLiveTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ProductLine line = ProductLine.valueOf(System.getenv("SIX_LINE_LIVE_PRODUCT"));
    private final String base = "http://127.0.0.1:9094";
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private String admin;
    private String approver;

    @Test
    void createListEditAndPauseThroughAdminRequestsWithoutRestart() throws Exception {
        String jdbcUrl = System.getenv("SIX_LINE_LIVE_JDBC_URL");
        assertThat(jdbcUrl).startsWith("jdbc:postgresql://127.0.0.1:").endsWith("/sixline_live");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "sixline_qa", "qa"));
        admin = adminFixture(jdbc, "requester");
        approver = adminFixture(jdbc, "approver");
        assertThat(get("/api/v1/runtime", null).path("productLines").get(0).asString()).isEqualTo(line.name());
        var started = processIdentities();
        verifyBusinessSettings();
        var storage = new InstrumentStorageService(new InstrumentRepository(jdbc), new InstrumentChangeLogRepository(jdbc),
                new InstrumentRiskBracketRepository(jdbc), new InstrumentIndexSourceRepository(jdbc), new AssetRepository(jdbc), json);
        var template = storage.latest(604, ProductLine.LINEAR_PERPETUAL).orElseThrow();
        var draft = (ObjectNode) json.valueToTree(template);
        draft.remove(List.of("changeId", "lastChangeId", "createdAt", "updatedAt", "baseAsset", "quoteAsset", "settleAsset", "contractValueAsset"));
        draft.putNull("instrumentId");
        draft.put("symbol", "QA-" + line.name() + "-" + run.toUpperCase(Locale.ROOT));
        draft.put("status", "DRAFT");
        draft.put("minNotionalUnits", 1);
        draft.put("minValidIndexSources", 1);
        draft.put("priceTickUnits", 10_000_000L);
        draft.put("quantityStepUnits", 100_000L);
        draft.put("notionalMultiplierUnits", line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY
                ? 100_000_000_000L : 10_000L);
        var source = ((ObjectNode) draft.path("indexSources").get(0)).deepCopy();
        source.put("source", "QA_LOOPBACK"); source.put("enabled", true);
        source.put("baseUrl", "http://127.0.0.1:19195"); source.put("path", "/ticker");
        source.put("sourceSymbol", "BTCUSDT"); source.put("parser", "BINANCE_BOOK_TICKER");
        source.put("quoteCurrency", "USDT"); source.put("targetQuoteCurrency", "USDT");
        source.put("websocketEnabled", true); source.put("websocketUrl", "ws://127.0.0.1:19194");
        source.put("websocketParser", "BINANCE_BOOK_TICKER"); source.put("websocketSubscribeMessage", "{}");
        draft.putArray("indexSources").add(source);
        draft.put("contractType", line.contractTypeCode());
        draft.put("instrumentType", switch (line) {
            case SPOT -> "SPOT";
            case LINEAR_PERPETUAL, INVERSE_PERPETUAL -> "PERPETUAL";
            case LINEAR_DELIVERY, INVERSE_DELIVERY -> "DELIVERY";
            case OPTION -> "OPTION";
        });
        if (line != ProductLine.LINEAR_PERPETUAL && line != ProductLine.INVERSE_PERPETUAL)
            for (var field : List.of("fundingIntervalHours", "interestRatePpm", "fundingRateCapPpm", "fundingRateFloorPpm")) draft.put(field, 0);
        if (line == ProductLine.SPOT) {
            draft.put("reduceOnlyEnabled", false);
            draft.putArray("riskLimitBrackets");
        }
        if (line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY) draft.put("settleAssetId", template.baseAssetId());
        if (line == ProductLine.LINEAR_DELIVERY || line == ProductLine.INVERSE_DELIVERY || line == ProductLine.OPTION) {
            draft.put("expiryTime", Instant.now().plusSeconds(86400).toString());
            draft.put("deliveryTime", Instant.now().plusSeconds(86700).toString());
            draft.put("settlementMethod", "CASH");
        }
        if (line == ProductLine.OPTION) {
            draft.put("underlyingInstrumentId", "604");
            draft.put("underlyingProductLine", "LINEAR_PERPETUAL");
            draft.put("strikePriceUnits", 5_000_000_000_000L);
            draft.put("optionType", "CALL");
            draft.put("optionExerciseStyle", "EUROPEAN");
            source.put("parser", "OPTION_RISK_TICKER"); source.put("websocketParser", "OPTION_RISK_TICKER");
            source.put("path", "/option?expiry=" + draft.path("expiryTime").asString());
            source.put("websocketUrl", "ws://127.0.0.1:19194/option?expiry=" + draft.path("expiryTime").asString());
            draft.putArray("indexSources").add(source);
        }
        // Routine configuration uses one administrator; funding the test accounts retains dual approval.
        var created = write("instrument-admin", "/upsert?reason=six-line-qa&expectedChangeId=0", draft, 200);
        int id = created.path("instrumentId").asInt();
        assertThat(id).isPositive();
        assertVisible(id, false);
        awaitSync(id, created.path("lastChangeId").asLong());
        var listed = changeStatus(id, created, "PRE_TRADING", 200);
        assertVisible(id, true);
        awaitSync(id, listed.path("lastChangeId").asLong());
        changeStatus(id, created, "TRADING", 409);
        var trading = changeStatus(id, listed, "TRADING", 200);
        awaitSync(id, trading.path("lastChangeId").asLong());
        if ("true".equals(System.getenv("SIX_LINE_LIVE_TRADING"))) exerciseTrading(jdbc, id, created);
        draft.put("instrumentId", id);
        draft.put("status", "TRADING");
        draft.put("makerFeeRatePpm", template.makerFeeRatePpm() + 1);
        var edited = write("instrument-admin", "/upsert?reason=edit-fee&expectedChangeId=" + trading.path("lastChangeId").asLong(), draft, 200);
        assertThat(edited.path("makerFeeRatePpm").asLong()).isEqualTo(template.makerFeeRatePpm() + 1);
        awaitSync(id, edited.path("lastChangeId").asLong());
        draft.put("quantityStepUnits", template.quantityStepUnits() * 2);
        write("instrument-admin", "/upsert?reason=invalid-units&expectedChangeId=" + edited.path("lastChangeId").asLong(), draft, 400);
        var halted = changeStatus(id, edited, "HALT", 200);
        awaitSync(id, halted.path("lastChangeId").asLong());
        assertVisible(id, true);
        assertThat(processIdentities()).isEqualTo(started);
        var changes = get("/api/v1/admin/gateway/instrument-admin/" + id + "/changes?productLine=" + line + "&limit=20", admin);
        assertThat(changes.size()).isEqualTo(5);
        System.out.printf("SIX_LINE_CONTRACT_PASS productLine=%s instrumentId=%d version=%d transitions=DRAFT,PRE_TRADING,TRADING,EDIT,HALT processes=%s%n",
                line, id, halted.path("lastChangeId").asLong(), started.keySet());
    }

    private String adminFixture(JdbcTemplate jdbc, String role) throws Exception {
        return participantFixture(jdbc, role, true).token;
    }

    private void verifyBusinessSettings() throws Exception {
        var price = get("/api/v1/admin/gateway/price-index/admin/business-settings?productLine=" + line, admin).path("config");
        var invalid = ((ObjectNode) price.path("settings")).deepCopy(); invalid.put("indexPollDelayMs", 0);
        write("price-index", "/admin/business-settings?productLine=" + line,
                Map.of("settings", invalid, "expectedVersion", price.path("version").asLong(), "reason", "reject invalid price interval"), 400);
        var candidate = ((ObjectNode) price.path("settings")).deepCopy(); candidate.put("indexPollDelayMs", 500);
        var saved = write("price-index", "/admin/business-settings?productLine=" + line,
                Map.of("settings", candidate, "expectedVersion", price.path("version").asLong(), "reason", "verify price settings hot save"), 200);
        write("price-index", "/admin/business-settings?productLine=" + line,
                Map.of("settings", candidate, "expectedVersion", price.path("version").asLong(), "reason", "reject stale price version"), 409);
        assertThat(saved.path("version").asLong()).isEqualTo(price.path("version").asLong() + 1);
        if (line != ProductLine.SPOT) {
            for (var service : List.of("liquidation", "insurance-admin", "adl")) {
                String path = service.equals("insurance-admin") ? "/runtime-config" : "/admin/runtime-config";
                var current = get("/api/v1/admin/gateway/" + service + path + "?productLine=" + line, admin);
                String field = service.equals("liquidation") ? "delayMs" : "scanDelayMs";
                write(service, path + "?productLine=" + line,
                        Map.of("expectedVersion", current.path("version").asLong(), field, 0, "reason", "invalid interval"), 400);
                var changed = write(service, path + "?productLine=" + line,
                        Map.of("expectedVersion", current.path("version").asLong(), field, 100, "reason", "verify lifecycle hot save"), 200);
                assertThat(changed.path("version").asLong()).isEqualTo(current.path("version").asLong() + 1);
                write(service, path + "?productLine=" + line,
                        Map.of("expectedVersion", current.path("version").asLong(), field, 200, "reason", "stale version"), 409);
            }
            if (line.isFundingProduct()) {
                var funding = get("/api/v1/admin/gateway/funding/admin/runtime-config?productLine=" + line, admin);
                var changed = write("funding", "/admin/runtime-config?productLine=" + line,
                        Map.of("expectedVersion", funding.path("version").asLong(), "calculationPublishDelayMs", 500,
                                "reason", "verify funding hot save"), 200);
                assertThat(changed.path("calculation").path("publishDelayMs").asLong()).isEqualTo(500);
            }
        }
        System.out.printf("SIX_LINE_SETTINGS_PASS productLine=%s price=true lifecycle=%s%n", line, line != ProductLine.SPOT);
    }

    private Participant participantFixture(JdbcTemplate jdbc, String role, boolean administrator) throws Exception {
        String email = "qa-" + role + "-" + run + "@surprising.test";
        String password = "SixLine-QA-" + run + "!";
        Long id = jdbc.queryForObject("INSERT INTO gateway_users(email,password_hash,email_verified_at) VALUES (?,?,now()) RETURNING user_id", Long.class,
                email, new PasswordHasher().hash(password));
        jdbc.update("INSERT INTO gateway_user_roles(user_id,role_id) SELECT ?,role_id FROM gateway_roles WHERE role_code=?", id,
                administrator ? "SUPER_ADMIN" : "USER");
        var auth = request("POST", "/api/v1/auth/login", json.writeValueAsString(Map.of("identifier", email, "password", password)), null, null, 200);
        String accessToken = auth.path("accessToken").asString();
        var claims = json.readTree(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).as("access JWT is valid for seven days").isEqualTo(604_800);
        return new Participant(id, accessToken);
    }

    private void exerciseTrading(JdbcTemplate jdbc, int instrumentId, JsonNode instrument) throws Exception {
        Participant user = participantFixture(jdbc, "trader", false);
        Participant maker = participantFixture(jdbc, "maker", false);
        String settlement = line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
        for (var actor : List.of(user, maker)) {
            fund(actor, settlement, settlement.equals("BTC") ? 1_000_000_000L : 10_000_000_000_000L);
            if (line == ProductLine.SPOT) fund(actor, "BTC", 1_000_000_000L);
        }
        String strategy = "qa-" + run;
        try (var core = SurprisingAeronClient.connect(line, List.of("127.0.0.1"), "127.0.0.1", Duration.ofSeconds(10))) {
            var before = combinedFunds(core, user.id, maker.id);
            user.connect();
            var settings = get("/api/v1/admin/gateway/market-maker/business-settings?productLine=" + line, admin);
            var business = ((ObjectNode) settings.path("settings")).deepCopy();
            ((ObjectNode) business.path("engine")).put("enabled", true);
            ((ObjectNode) business.path("trade")).put("enabled", false);
            ((ObjectNode) business.path("referenceMarket")).put("enabled", false);
            write("market-maker", "/business-settings?productLine=" + line,
                    Map.of("settings", business, "expectedVersion", settings.path("version").asLong(), "reason", "six-line live QA"), 200);
            var definition = new LinkedHashMap<String, Object>();
            definition.put("strategyId", strategy); definition.put("productLine", line.name());
            definition.put("enabled", true); definition.put("accountIds", List.of(maker.id));
            definition.put("instrumentIds", List.of(String.valueOf(instrumentId)));
            definition.put("baseQuantitySteps", 10); definition.put("marginMode", "CROSS");
            definition.put("spreadTicks", 20); definition.put("levelSpacingTicks", 10);
            definition.put("maxInventorySteps", 1_000_000); definition.put("maxInventorySkewPpm", 0);
            definition.put("orderLevels", 3); definition.put("initialAnchorPriceTicks", line == ProductLine.OPTION ? 10_000 : 500_000); definition.put("version", 0);
            write("market-maker", "/strategy-definitions?reason=six-line-qa", definition, 200);
            JsonNode book = null;
            for (int attempt = 0; attempt < 180; attempt++) {
                book = request("GET", "/api/v1/gateway/trading-market/orderbook?instrumentId=" + instrumentId + "&depth=10",
                        "", user.token, null, 200, 404, 503);
                if (!book.path("bids").isEmpty() && !book.path("asks").isEmpty()
                        && book.has("bids") && book.has("asks")) break;
                Thread.sleep(500);
            }
            assertThat(book.path("bids").size()).as("maker bids " + book).isPositive();
            assertThat(book.path("asks").size()).as("maker asks " + book).isPositive();
            long bid = book.path("bids").get(0).path("priceTicks").asLong();
            var resting = place(user, instrumentId, "BUY", "LIMIT", bid * 99 / 100, "GTC", false);
            assertThat(resting.path("status").asString()).isEqualTo("ACCEPTED");
            var canceled = orderResult(request("POST", "/api/v1/gateway/trading/cancel",
                    json.writeValueAsString(Map.of("userId", user.id, "orderId", resting.path("orderId").asLong())), user.token, null, 200));
            assertThat(canceled.path("status").asString()).isEqualTo("CANCELED");
            var bought = place(user, instrumentId, "BUY", line == ProductLine.SPOT ? "LIMIT" : "MARKET",
                    line == ProductLine.SPOT ? book.path("asks").get(0).path("priceTicks").asLong() * 101 / 100 : 0, "IOC", false);
            assertThat(bought.path("status").asString()).isEqualTo("FILLED");
            if (line != ProductLine.SPOT) verifyCurrentRisk(core, user, instrumentId, settlement, instrument);
            var sold = place(user, instrumentId, "SELL", line == ProductLine.SPOT ? "LIMIT" : "MARKET",
                    line == ProductLine.SPOT ? bid * 99 / 100 : 0, "IOC", line != ProductLine.SPOT);
            assertThat(sold.path("status").asString()).isEqualTo("FILLED");
            for (int attempt = 0; attempt < 100 && user.executions.size() < 2; attempt++) Thread.sleep(100);
            assertThat(user.errors).isEmpty();
            assertThat(user.executions.size()).as("execution reports arrived over WebSocket").isGreaterThanOrEqualTo(2);
            Set<Long> executionOrders = new HashSet<>();
            for (var event : user.executions.values()) {
                assertThat(event.path("userId").asLong()).isEqualTo(user.id);
                assertThat(event.path("productLine").asString()).isEqualTo(line.name());
                executionOrders.add(event.path("data").path("value").path("orderId").asLong());
            }
            assertThat(executionOrders).contains(bought.path("orderId").asLong(), sold.path("orderId").asLong());
            write("market-maker", "/strategies/" + strategy + "/pause?productLine=" + line, null, 200);
            for (int attempt = 0; attempt < 100; attempt++) {
                if (userState(core, maker.id).reservations().isEmpty()) break;
                Thread.sleep(100);
            }
            for (var actor : List.of(user, maker)) {
                var state = userState(core, actor.id);
                assertThat(state.reservations()).isEmpty();
                assertThat(state.positions()).allMatch(p -> p.signedQuantitySteps() == 0 && p.positionMarginUnits() == 0);
                assertThat(state.balances()).allMatch(b -> b.lockedUnits() == 0);
            }
            assertThat(combinedFunds(core, user.id, maker.id)).as("users + maker + treasury asset conservation").isEqualTo(before);
            System.out.printf("SIX_LINE_TRADING_PASS productLine=%s instrumentId=%d trader=%d maker=%d executions=%d fundsDifference=0%n",
                    line, instrumentId, user.id, maker.id, user.executions.size());
        } finally {
            if (user.socket != null) user.socket.sendClose(WebSocket.NORMAL_CLOSURE, "QA complete").join();
        }
    }

    private void verifyCurrentRisk(SurprisingAeronClient core, Participant actor, int instrumentId, String settlement, JsonNode instrument) throws Exception {
        var position = userState(core, actor.id).positions().stream().filter(p -> p.instrumentId().equals(String.valueOf(instrumentId))).findFirst().orElseThrow();
        assertThat(position.signedQuantitySteps()).isEqualTo(1);
        var rows = get("/api/v1/gateway/risk/positions/latest?userId=" + actor.id, actor.token).path("positions");
        JsonNode risk = null;
        for (var candidate : rows) if (candidate.path("instrumentId").asInt() == instrumentId) risk = candidate;
        assertThat(risk).isNotNull();
        long mark = risk.path("markPriceTicks").asLong();
        assertThat(mark).isPositive();
        var quantity = java.math.BigDecimal.valueOf(position.signedQuantitySteps());
        var entryPrice = java.math.BigDecimal.valueOf(position.entryPriceTicks());
        var markPrice = java.math.BigDecimal.valueOf(mark);
        var multiplier = java.math.BigDecimal.valueOf(instrument.path("notionalMultiplierUnits").asLong());
        var tick = java.math.BigDecimal.valueOf(instrument.path("priceTickUnits").asLong());
        boolean inverse = line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY;
        long pnl = inverse ? quantity.multiply(multiplier).multiply(java.math.BigDecimal.valueOf(100_000_000L))
                .multiply(markPrice.subtract(entryPrice)).divide(entryPrice.multiply(markPrice).multiply(tick), 0, java.math.RoundingMode.HALF_UP).longValueExact()
                : quantity.multiply(markPrice.subtract(entryPrice)).multiply(multiplier).longValueExact();
        long notional = inverse ? quantity.abs().multiply(multiplier).multiply(java.math.BigDecimal.valueOf(100_000_000L))
                .divide(markPrice.multiply(tick), 0, java.math.RoundingMode.HALF_UP).longValueExact()
                : quantity.abs().multiply(markPrice).multiply(multiplier).longValueExact();
        assertThat(risk.path("unrealizedPnlUnits").asLong()).as("independently calculated current PnL").isEqualTo(pnl);
        assertThat(risk.path("notionalUnits").asLong()).isEqualTo(notional);
        long maintenanceRate = instrument.path("maintenanceMarginRatePpm").asLong();
        for (var bracket : instrument.path("riskLimitBrackets")) if (bracket.path("notionalFloorUnits").asLong() <= notional)
            maintenanceRate = bracket.path("maintenanceMarginRatePpm").asLong();
        long maintenance = line == ProductLine.OPTION ? 0 : java.math.BigDecimal.valueOf(notional)
                .multiply(java.math.BigDecimal.valueOf(maintenanceRate)).divide(java.math.BigDecimal.valueOf(1_000_000), 0, java.math.RoundingMode.CEILING).longValueExact();
        assertThat(risk.path("maintenanceMarginUnits").asLong()).isEqualTo(maintenance);
        var account = get("/api/v1/gateway/risk/account/latest?userId=" + actor.id + "&accountType=" + line.accountTypeCode() + "&settleAsset=" + settlement, actor.token);
        var balance = userState(core, actor.id).balances().stream().filter(b -> b.asset().equals(settlement)).findFirst().orElseThrow();
        long wallet = Math.addExact(balance.availableUnits(), balance.lockedUnits());
        long equity = Math.addExact(wallet, line == ProductLine.OPTION ? notional : pnl);
        assertThat(account.path("walletBalanceUnits").asLong()).isEqualTo(wallet);
        assertThat(account.path("unrealizedPnlUnits").asLong()).isEqualTo(pnl);
        assertThat(account.path("equityUnits").asLong()).isEqualTo(equity);
        assertThat(account.path("maintenanceMarginUnits").asLong()).isEqualTo(maintenance);
        assertThat(account.path("marginRatioPpm").asLong()).isEqualTo(maintenance * 1_000_000 / equity);
        System.out.printf("SIX_LINE_RISK_PASS productLine=%s instrumentId=%d pnl=%d notional=%d maintenance=%d equity=%d%n",
                line, instrumentId, pnl, notional, maintenance, equity);
    }

    private void fund(Participant actor, String asset, long units) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("userId", actor.id); body.put("asset", asset); body.put("amountUnits", units);
        body.put("referenceId", "qa-" + run + "-" + actor.id + "-" + asset); body.put("reason", "isolated live QA funding");
        if (line != ProductLine.SPOT) body.put("accountType", line.accountTypeCode());
        write("account", line == ProductLine.SPOT ? "/balance-adjustments" : "/product-balance-adjustments", body, 200);
    }

    private JsonNode place(Participant actor, int instrumentId, String side, String type, long price, String tif, boolean reduceOnly) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("userId", actor.id); body.put("clientOrderId", "qa-" + UUID.randomUUID());
        body.put("instrumentId", String.valueOf(instrumentId)); body.put("side", side); body.put("orderType", type);
        body.put("priceTicks", price); body.put("quantitySteps", 1); body.put("timeInForce", tif);
        body.put("marginMode", "CROSS"); body.put("positionSide", "NET"); body.put("reduceOnly", reduceOnly); body.put("postOnly", false);
        return orderResult(request("POST", "/api/v1/gateway/trading", json.writeValueAsString(body), actor.token, null, 200));
    }

    private JsonNode orderResult(JsonNode receipt) {
        JsonNode order = receipt.has("orderId") ? receipt : receipt.path("result");
        assertThat(order.has("orderId")).as("terminal order response " + receipt).isTrue();
        return order;
    }

    private CoreResponse query(SurprisingAeronClient core, CoreMessageType type, long user) {
        long now = System.currentTimeMillis();
        var response = core.submit(new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), line,
                CommandSource.OPERATIONS, 0, now, user, now, now), new byte[0]));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return response;
    }

    private CoreUserStateView userState(SurprisingAeronClient core, long user) {
        return CoreStateQueryCodec.decodeUserState(query(core, CoreMessageType.USER_STATE_QUERY, user).data());
    }

    private Map<String, Long> combinedFunds(SurprisingAeronClient core, long user, long maker) {
        var funds = new TreeMap<String, Long>();
        for (long actor : List.of(user, maker)) for (var balance : userState(core, actor).balances())
            funds.merge(balance.asset(), Math.addExact(balance.availableUnits(), balance.lockedUnits()), Math::addExact);
        for (var balance : CoreStateQueryCodec.decodeTreasuryState(query(core, CoreMessageType.TREASURY_STATE_QUERY, 0).data()))
            funds.merge(balance.asset(), java.util.stream.LongStream.of(balance.feeBalanceUnits(), balance.insuranceBalanceUnits(),
                    balance.liquidationFeeBalanceUnits(), balance.fundingResidualBalanceUnits(),
                    balance.roundingResidualBalanceUnits(), balance.clearingPnlBalanceUnits(), -balance.insuranceDeficitUnits())
                    .reduce(0, Math::addExact), Math::addExact);
        funds.entrySet().removeIf(entry -> entry.getValue() == 0);
        return funds;
    }

    private final class Participant implements WebSocket.Listener {
        final long id;
        final String token;
        final CompletableFuture<Void> subscribed = new CompletableFuture<>();
        final Map<String, JsonNode> executions = new ConcurrentHashMap<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final StringBuilder fragments = new StringBuilder();
        WebSocket socket;
        Participant(long id, String token) { this.id = id; this.token = token; }
        void connect() {
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://127.0.0.1:9094/ws/v1"), this).join();
            socket.sendText(json.writeValueAsString(Map.of("op", "authenticate", "id", "auth", "token", token)), true).join();
            subscribed.orTimeout(15, TimeUnit.SECONDS).join();
        }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                var event = json.readTree(fragments.toString()); fragments.setLength(0);
                String op = event.path("op").asString();
                if (op.equals("authenticated")) socket.sendText(json.writeValueAsString(Map.of("op", "subscribe", "id", "executions",
                        "channel", "executionReports", "productLine", line.name())), true);
                if (op.equals("subscribed")) subscribed.complete(null);
                if (op.equals("error")) { errors.add(event.toString()); subscribed.completeExceptionally(new IllegalStateException(event.toString())); }
                if (op.equals("event") && event.path("channel").asString().equals("executionReports"))
                    executions.put(event.path("data").path("version").asString(), event);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket socket, Throwable failure) { errors.add(failure.toString()); subscribed.completeExceptionally(failure); }
    }

    private JsonNode changeStatus(int id, JsonNode previous, String status, int expected) throws Exception {
        return write("instrument-admin", "/" + id + "/status?productLine=" + line + "&status=" + status
                + "&reason=six-line-qa&expectedChangeId=" + previous.path("lastChangeId").asLong(), null, expected);
    }

    private JsonNode write(String service, String suffix, Object body, int expected) throws Exception {
        String path = "/api/v1/admin/gateway/" + service + suffix;
        String serialized = body == null ? "" : json.writeValueAsString(body);
        if (!service.equals("account")) {
            return request("POST", path, serialized, admin, null, expected);
        }
        URI uri = URI.create(path);
        String digest = serialized.isEmpty() ? null
                : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(StandardCharsets.UTF_8)));
        var approvalBody = new LinkedHashMap<String, Object>();
        approvalBody.put("service", service); approvalBody.put("httpMethod", "POST");
        approvalBody.put("requestPath", uri.getPath()); approvalBody.put("queryString", uri.getRawQuery());
        approvalBody.put("requestBodySha256", digest); approvalBody.put("reason", "isolated six-product-line QA");
        var approval = request("POST", "/api/v1/admin/approvals", json.writeValueAsString(approvalBody), admin, null, 200);
        String id = approval.path("approvalId").asString();
        request("POST", "/api/v1/admin/approvals/" + id + "/approve", "{\"reason\":\"QA approved\"}", approver, null, 200);
        return request("POST", path, serialized, admin, id, expected);
    }

    private void assertVisible(int id, boolean expected) throws Exception {
        var list = get("/api/v1/gateway/instrument/list?productLine=" + line, null);
        boolean found = false;
        for (var row : list.path("instruments")) found |= row.path("instrumentId").asInt() == id;
        assertThat(found).as("public listing " + id + " response=" + list).isEqualTo(expected);
    }

    private void awaitSync(int id, long version) throws Exception {
        JsonNode state = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            state = request("GET", "/api/v1/admin/gateway/trading-orders/instrument-sync/" + id + "?productLine=" + line,
                    "", admin, null, 200, 400);
            if (state.path("status").asInt() == 400) {
                assertThat(state.path("message").asString()).isEqualTo("instrument not found in configuration cache");
                Thread.sleep(200);
                continue;
            }
            if (state.path("state").asString().equals("APPLIED") && state.path("appliedChangeId").asLong() == version) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Core did not apply version " + version + ": " + state);
    }

    private Map<Long, Instant> processIdentities() {
        var result = new TreeMap<Long, Instant>();
        ProcessHandle.allProcesses().filter(p -> p.info().commandLine().orElse("").contains("surprising-six-lines-20261005")
                && p.info().command().orElse("").endsWith("/java") && p.info().commandLine().orElse("").contains("-jar"))
                .forEach(p -> result.put(p.pid(), p.info().startInstant().orElseThrow()));
        assertThat(result.size()).isGreaterThanOrEqualTo(line == ProductLine.SPOT ? 5 : 6);
        return result;
    }

    private JsonNode get(String path, String token) throws Exception { return request("GET", path, "", token, null, 200); }

    private JsonNode request(String method, String path, String body, String token, String approval, int... expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("X-Product-Line", line.name());
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (approval != null) builder.header("X-Admin-Approval-Id", approval);
        var response = http.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isIn(Arrays.stream(expected).boxed().toList());
        return json.readTree(response.body());
    }
}
