package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.asset.repository.AssetRepository;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.repository.*;
import com.surprising.instrument.provider.service.InstrumentStorageService;
import com.surprising.instrument.provider.service.InstrumentLifecycleReadinessService;
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
    private int optionCompanion;
    private Map<String, Object> triggerQueryFixture;
    private Map<String, Object> tradeQueryFixture;
    private Map<String, Object> userFillFixture;
    private Map<String, Object> statisticsFixture;
    private String deliveryProtectionPhase;

    @Test
    void createListEditAndPauseThroughAdminRequestsWithoutRestart() throws Exception {
        String jdbcUrl = System.getenv("SIX_LINE_LIVE_JDBC_URL");
        assertThat(jdbcUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/(sixline_live|surprising_spot_[0-9_]+|surprising_delivery_[0-9_]+|surprising_inverse_delivery_[0-9_]+|surprising_option_[0-9_]+)");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl,
                System.getenv().getOrDefault("SIX_LINE_LIVE_JDBC_USER", "sixline_qa"),
                System.getenv().getOrDefault("SIX_LINE_LIVE_JDBC_PASSWORD", "qa")));
        admin = adminFixture(jdbc, "requester");
        approver = adminFixture(jdbc, "approver");
        enableLocalProduct();
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
        if (line == ProductLine.OPTION && "true".equals(System.getenv("SIX_LINE_LIVE_OPTION_REFERENCE"))) {
            var put = draft.deepCopy();
            put.put("symbol", "QA-OPTION-PUT-" + run.toUpperCase(Locale.ROOT));
            put.putNull("instrumentId");
            put.put("optionType", "PUT");
            // 与 CALL=1000、F=50500、K=50000 一致的 PUT=500，满足零贴现平价关系。
            for (var configuredSource : put.path("indexSources")) {
                var input = (ObjectNode) configuredSource;
                input.put("path", input.path("path").asString() + "&premium=500");
                input.put("websocketUrl", input.path("websocketUrl").asString() + "&premium=500");
            }
            var putCreated = write("instrument-admin", "/upsert?reason=option-reference-qa&expectedChangeId=0", put, 200);
            optionCompanion = putCreated.path("instrumentId").asInt();
            var putListed = changeStatus(optionCompanion, putCreated, "PRE_TRADING", 200);
            var putTrading = changeStatus(optionCompanion, putListed, "TRADING", 200);
            awaitSync(optionCompanion, putTrading.path("lastChangeId").asLong());
        }
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
        if (line == ProductLine.OPTION && "true".equals(System.getenv("SIX_LINE_LIVE_EXPIRY"))) exerciseOptionExpiryMatrix(jdbc, created);
        else if (line == ProductLine.LINEAR_DELIVERY || line == ProductLine.INVERSE_DELIVERY) {
            if (System.getenv("SIX_LINE_LIVE_UI_FIXTURE") != null) exerciseDeliveryClients(jdbc, created);
            if ("true".equals(System.getenv("SIX_LINE_LIVE_EXPIRY"))) exerciseDeliveryExpiry(jdbc, created);
        }
        assertThat(processIdentities()).isEqualTo(started);
        System.out.printf("SIX_LINE_CONTRACT_PASS productLine=%s instrumentId=%d version=%d transitions=DRAFT,PRE_TRADING,TRADING,EDIT,HALT processes=%s%n",
                line, id, halted.path("lastChangeId").asLong(), started.keySet());
    }

    /** Protect the real maker short, then retain both terminal legs for history/receipt reads. */
    private void verifyMakerTriggerHistory(Participant maker, int instrumentId) throws Exception {
        var legs = new ArrayList<Map<String, Object>>();
        for (boolean tp : new boolean[]{true, false}) {
            var leg = new LinkedHashMap<String, Object>();
            leg.put("userId", maker.id);
            leg.put("clientTriggerOrderId", "query-" + run + (tp ? "-tp" : "-sl"));
            leg.put("ocoGroupId", "query-" + run);
            leg.put("instrumentId", String.valueOf(instrumentId));
            leg.put("side", "BUY");
            leg.put("triggerType", tp ? "TAKE_PROFIT" : "STOP_LOSS");
            leg.put("triggerPriceTicks", tp ? 8000 : 12000);
            leg.put("priceSource", "LAST");
            leg.put("orderType", "MARKET"); leg.put("timeInForce", "IOC");
            leg.put("quantitySteps", 1); leg.put("priceTicks", 0);
            leg.put("marginMode", "CROSS"); leg.put("positionSide", "NET"); leg.put("reduceOnly", true);
            legs.add(leg);
        }
        var result = request("POST", "/api/v1/gateway/trading-trigger/batch",
                json.writeValueAsString(Map.of("orders", legs, "atomic", true)), maker.token, null, 200);
        assertThat(result.path("completed").asInt()).isEqualTo(2);
        var ids = new ArrayList<String>();
        for (var row : result.path("results")) {
            long id = row.path("order").path("triggerOrderId").asLong();
            ids.add(Long.toString(id));
            request("POST", "/api/v1/gateway/trading-trigger/cancel",
                    json.writeValueAsString(Map.of("userId", maker.id, "triggerOrderId", id)), maker.token, null, 200);
            JsonNode terminal = null;
            for (int i = 0; i < 100; i++) {
                terminal = request("GET", "/api/v1/gateway/trading-trigger/" + id + "?userId=" + maker.id,
                        "", maker.token, null, 200, 404, 503);
                if (terminal.path("status").asString().equals("CANCELED")) break;
                Thread.sleep(100);
            }
            assertThat(terminal.path("status").asString()).isEqualTo("CANCELED");
            var timeline = get("/api/v1/admin/gateway/trading-trigger/" + id + "/timeline?productLine=" + line, admin);
            assertThat(timeline.path("order").path("triggerOrderId").asLong()).isEqualTo(id);
            assertThat(timeline.path("order").path("priceSource").asString()).isEqualTo("LAST");
        }
        String receiptPath = "/api/v1/gateway/trading-trigger/oco-submission?userId=" + maker.id
                + "&takeProfitClientId=query-" + run + "-tp&stopLossClientId=query-" + run + "-sl";
        JsonNode receipt = null;
        for (int i = 0; i < 100; i++) {
            receipt = request("GET", receiptPath, "", maker.token, null, 200, 404, 503);
            if (receipt.path("outcome").asString().equals("TERMINAL")) break;
            Thread.sleep(100);
        }
        assertThat(receipt.path("outcome").asString()).isEqualTo("TERMINAL");
        assertThat(receipt.path("code").asString()).isEqualTo("NONE");
        triggerQueryFixture = Map.of("userId", maker.id, "accessToken", maker.token, "triggerIds", ids, "receiptPath", receiptPath);
        System.out.println("OPTION_TRIGGER_READ_PASS makerOcoReceipt=true terminalUserLookup=2 adminTimeline=2 source=LAST");
    }

    private void enableLocalProduct() throws Exception {
        for (var status : get("/api/v1/admin/product-lines", admin)) {
            var configuration = status.path("configuration");
            if (line.name().equals(configuration.path("productLine").asString()) && !configuration.path("enabled").asBoolean())
                request("POST", "/api/v1/admin/product-lines/" + line + "/enable",
                        json.writeValueAsString(Map.of("expectedVersion", configuration.path("configurationVersion").asLong(),
                                "reason", "isolated product live QA")), admin, null, 200);
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        while (get("/api/v1/runtime", null).path("productLines").isEmpty()) {
            assertThat(System.nanoTime()).as("selected local Core activation").isLessThan(deadline);
            Thread.sleep(200);
        }
    }

    /** Optional local UI handoff: keep the real maker running until both clients finish. */
    private void awaitClientVerification(Participant user, Participant maker, int instrumentId, long bid) throws Exception {
        String output = System.getenv("SIX_LINE_LIVE_UI_FIXTURE");
        if (output == null || output.isBlank()) return;
        var fixture = java.nio.file.Path.of(output).toAbsolutePath().normalize();
        assertThat(fixture.toString()).startsWith(line == ProductLine.SPOT
                ? "/tmp/surprising-spot-fused-" : line == ProductLine.OPTION ? "/tmp/surprising-option-fused-" : line == ProductLine.INVERSE_DELIVERY ? "/tmp/surprising-inverse-delivery-fused-" : "/tmp/surprising-linear-delivery-fused-");
        var done = fixture.resolveSibling(fixture.getFileName() + ".done");
        assertThat(java.nio.file.Files.exists(fixture)).isFalse();
        assertThat(java.nio.file.Files.exists(done)).isFalse();
        try {
            var content = new LinkedHashMap<String, Object>(Map.of(
                    "userId", user.id, "accessToken", user.token, "instrumentId", String.valueOf(instrumentId),
                    "makerId", maker.id, "bidPriceTicks", bid, "runId", System.getenv("SIX_LINE_LIVE_RUN_ID"), "productLine", line.name(), "optionCompanionId", optionCompanion,
                    "session", Map.of("accessToken", user.token, "user", Map.of("userId", user.id, "username", "live-" + line.name()))));
            if (triggerQueryFixture != null) {
                content.put("triggerQuery", triggerQueryFixture);
                content.put("adminAccessToken", admin);
            }
            if (tradeQueryFixture != null) {
                content.put("tradeQuery", tradeQueryFixture);
                content.put("adminAccessToken", admin);
            }
            if (userFillFixture != null) content.put("userFills", userFillFixture);
            if(statisticsFixture!=null) {
                content.put("statistics",statisticsFixture);content.put("adminAccessToken",admin);
            }
            if (deliveryProtectionPhase != null) content.put("deliveryProtectionPhase", deliveryProtectionPhase);
            java.nio.file.Files.writeString(fixture, json.writeValueAsString(content),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            java.nio.file.Files.setPosixFilePermissions(fixture, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            System.out.printf("CLIENT_FIXTURE_READY productLine=%s instrumentId=%d userId=%d makerId=%d%n", line, instrumentId, user.id, maker.id);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(4);
            while (!java.nio.file.Files.exists(done)) {
                assertThat(System.nanoTime()).as("bounded browser/client handoff").isLessThan(deadline);
                Thread.sleep(250);
            }
            assertThat(java.nio.file.Files.readString(done).trim()).isEqualTo("PASS");
            if (triggerQueryFixture != null || tradeQueryFixture != null || statisticsFixture!=null) System.out.printf("ADMIN_QUERY_HANDOFF_PASS productLine=%s browser=true%n", line);
            else System.out.printf("CLIENT_HANDOFF_PASS productLine=%s browserAndFlutter=true%n", line);
        } finally {
            java.nio.file.Files.deleteIfExists(fixture);
            java.nio.file.Files.deleteIfExists(done);
        }
    }

    /** A separate contract enters SETTLING once; both clients close one of two contracts without reopening it. */
    private void exerciseDeliveryClients(JdbcTemplate jdbc, JsonNode template) throws Exception {
        var draft = ((ObjectNode) template).deepCopy();
        draft.remove(List.of("changeId", "lastChangeId", "createdAt", "updatedAt", "baseAsset", "quoteAsset", "settleAsset", "contractValueAsset"));
        draft.putNull("instrumentId"); draft.put("symbol", "QA-DELIVERY-CLOSE-" + run.toUpperCase(Locale.ROOT));
        draft.put("status", "DRAFT");
        var created = write("instrument-admin", "/upsert?reason=delivery-close-qa&expectedChangeId=0", draft, 200);
        int id = created.path("instrumentId").asInt();
        var listed = changeStatus(id, created, "PRE_TRADING", 200);
        var trading = changeStatus(id, listed, "TRADING", 200);
        awaitSync(id, trading.path("lastChangeId").asLong());
        var user = participantFixture(jdbc, "delivery-trader", false);
        var maker = participantFixture(jdbc, "delivery-maker", false);
        String settlementAsset = line == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
        long initialFunds = line == ProductLine.INVERSE_DELIVERY ? 1_000_000_000L : 10_000_000_000_000L;
        fund(user, settlementAsset, initialFunds); fund(maker, settlementAsset, initialFunds);
        boolean protection = "true".equals(System.getenv("SIX_LINE_LIVE_DELIVERY_PROTECTION"));
        if (protection) assertThat(line).isIn(ProductLine.LINEAR_DELIVERY, ProductLine.INVERSE_DELIVERY);
        String strategy = "qa-delivery-protection-" + run;
        boolean strategyCreated = false;
        try (var core = SurprisingAeronClient.connect(line, List.of("127.0.0.1"), "127.0.0.1", Duration.ofSeconds(10))) {
            var before = combinedFunds(core, user.id, maker.id);
            if (protection) user.connect(id);
            place(maker, id, "SELL", "LIMIT", 500_000, "GTC", false, 2);
            place(user, id, "BUY", "LIMIT", 500_000, "IOC", false, 2);
            if (protection) {
                var definition = new LinkedHashMap<String, Object>();
                definition.put("strategyId", strategy); definition.put("productLine", line.name());
                definition.put("enabled", true); definition.put("accountIds", List.of(maker.id));
                definition.put("instrumentIds", List.of(String.valueOf(id))); definition.put("baseQuantitySteps", 10);
                definition.put("marginMode", "CROSS"); definition.put("spreadTicks", 20); definition.put("levelSpacingTicks", 10);
                definition.put("maxInventorySteps", 1_000_000); definition.put("maxInventorySkewPpm", 0);
                definition.put("orderLevels", 3); definition.put("initialAnchorPriceTicks", 500_000); definition.put("version", 0);
                write("market-maker", "/strategy-definitions?reason=delivery-protection-qa", definition, 200);
                strategyCreated = true;
                long ready = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while (userState(core, maker.id).reservations().size() < 2) {
                    assertThat(System.nanoTime()).as("real maker quote readiness").isLessThan(ready);
                    Thread.sleep(100);
                }
                deliveryProtectionPhase = "TRADING";
                awaitClientVerification(user, maker, id, 500_000);
                var directory = java.nio.file.Path.of(System.getenv("SIX_LINE_LIVE_UI_FIXTURE")).getParent();
                var web = json.readTree(java.nio.file.Files.readString(directory.resolve("delivery-protection-web-evidence.json")));
                var client = json.readTree(java.nio.file.Files.readString(directory.resolve("delivery-protection-client-evidence.json")));
                for (var trigger : web.path("triggerIds")) {
                    var retained = CoreTriggerOrderCodec.decodeList(query(core, CoreMessageType.TRIGGER_ORDER_QUERY, user.id,
                            CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(Long.parseLong(trigger.asString()), "", 0, 1))).data()).getFirst();
                    assertThat(retained.status()).isEqualTo(CoreTriggerOrderStatus.CANCELED);
                }
                assertThat(web.path("triggerIds").size()).isEqualTo(3);
                assertThat(client.path("triggerIds").size()).isEqualTo(2);
                for (int i = 0; i < 2; i++) {
                    var retained = CoreTriggerOrderCodec.decodeList(query(core, CoreMessageType.TRIGGER_ORDER_QUERY, user.id,
                            CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(Long.parseLong(client.path("triggerIds").get(i).asString()), "", 0, 1))).data()).getFirst();
                    assertThat(retained.status()).isEqualTo(i == 0 ? CoreTriggerOrderStatus.TRIGGERED : CoreTriggerOrderStatus.CANCELED);
                    if (i == 0) assertThat(retained.placedOrderId()).isPositive();
                }
                assertThat(userState(core, user.id).positions()).anyMatch(pos -> pos.signedQuantitySteps() == 1);
                assertThat(userState(core, maker.id).positions()).anyMatch(pos -> pos.signedQuantitySteps() == -1);
                write("market-maker", "/strategies/" + strategy + "/pause?productLine=" + line, null, 200);
                System.out.println(line + "_PROTECTION_CORE_PASS webCanceled=3 clientTriggered=1 siblingCanceled=1 remainingPosition=1");
            }
            var settling = changeStatus(id, trading, "SETTLING", 200);
            awaitSync(id, settling.path("lastChangeId").asLong());
            assertThat(get("/api/v1/gateway/instrument/list?productLine=" + line, null).toString()).contains("QA-DELIVERY-CLOSE-" + run.toUpperCase(Locale.ROOT));
            place(maker, id, "BUY", "LIMIT", 500_000, "GTC", true, protection ? 1 : 2);
            if (protection) {
                deliveryProtectionPhase = "SETTLING";
                var legs = new ArrayList<Map<String, Object>>();
                for (boolean tp : new boolean[]{true, false}) {
                    var leg = new LinkedHashMap<String, Object>();
                    leg.put("userId", user.id); leg.put("clientTriggerOrderId", "settling-" + run + (tp ? "-tp" : "-sl"));
                    leg.put("ocoGroupId", "settling-" + run); leg.put("instrumentId", String.valueOf(id));
                    leg.put("side", "SELL"); leg.put("triggerType", tp ? "TAKE_PROFIT" : "STOP_LOSS");
                    leg.put("triggerPriceTicks", tp ? 600_000 : 400_000); leg.put("orderType", "MARKET");
                    leg.put("timeInForce", "IOC"); leg.put("priceTicks", 0); leg.put("quantitySteps", 1);
                    leg.put("marginMode", "CROSS"); leg.put("positionSide", "NET"); legs.add(leg);
                }
                request("POST", "/api/v1/gateway/trading-trigger", json.writeValueAsString(legs.getFirst()), user.token, null, 409);
                request("POST", "/api/v1/gateway/trading-trigger/batch", json.writeValueAsString(Map.of("atomic", true, "orders", legs)), user.token, null, 409);
                System.out.println(line + "_SETTLING_TRIGGER_REJECT_PASS single=409 oco=409 directReductionStillAllowed=true");
            }
            long leverageDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            JsonNode leverage;
            do {
                leverage = request("GET", "/api/v1/gateway/trading-leverage/settings?userId=" + user.id
                        + "&instrumentId=" + id + "&marginMode=CROSS", "", user.token, null, 200, 503);
                if (leverage.path("productLine").asString().equals(line.name())) break;
                assertThat(System.nanoTime()).as("bounded realtime leverage readiness").isLessThan(leverageDeadline);
                Thread.sleep(100);
            } while (true);
            assertThat(leverage.path("instrumentId").asInt()).isEqualTo(id);
            assertThat(leverage.path("leveragePpm").asLong()).isPositive();
            awaitClientVerification(user, maker, id, 500_000);
            for (var actor : List.of(user, maker)) {
                var state = userState(core, actor.id);
                assertThat(state.reservations()).isEmpty();
                assertThat(state.positions()).allMatch(p -> p.signedQuantitySteps() == 0 && p.positionMarginUnits() == 0);
                assertThat(state.balances()).allMatch(b -> b.lockedUnits() == 0);
            }
            assertThat(combinedFunds(core, user.id, maker.id)).as("delivery close funds conservation").isEqualTo(before);
            if (protection) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while (user.trades.size() < 3 || user.executions.size() < 3) {
                    assertThat(System.nanoTime()).as("trigger child and settling reduction realtime events").isLessThan(deadline);
                    Thread.sleep(100);
                }
                assertThat(user.errors).isEmpty();
                JsonNode recent;
                do {
                    recent = get("/api/v1/gateway/candlestick/trades/recent?instrumentId=" + id + "&limit=50", user.token);
                    if (recent.path("trades").size() >= 3) break;
                    assertThat(System.nanoTime()).as("committed recent-trade projection").isLessThan(deadline);
                    Thread.sleep(100);
                } while (true);
                var ids = new HashSet<String>();
                for (var trade : recent.path("trades")) ids.add(trade.path("tradeId").asString());
                assertThat(ids).containsExactlyInAnyOrderElementsOf(user.trades.keySet());
                assertThat(ids).hasSize(3);
                System.out.println(line + "_PROTECTION_REALTIME_PASS trades=3 wsRestIdentitiesMatch=true positions=0 reservations=0 locked=0 fundsDifference=0");
            }
            System.out.printf("%s_CLIENTS_PASS instrumentId=%d trader=%d maker=%d status=SETTLING positions=0 locked=0 fundsDifference=0%n", line, id, user.id, maker.id);
        } finally {
            if (strategyCreated) write("market-maker", "/strategies/" + strategy + "/pause?productLine=" + line, null, 200);
            if (user.socket != null) user.socket.abort();
        }
    }

    /** 真实时钟到期，排空普通订单，再经管理入口确认结算价；资金及流水均不能靠 UI 猜测。 */
    private void exerciseDeliveryExpiry(JdbcTemplate jdbc, JsonNode template) throws Exception {
        var draft = ((ObjectNode) template).deepCopy();
        draft.remove(List.of("changeId", "lastChangeId", "createdAt", "updatedAt", "baseAsset", "quoteAsset", "settleAsset", "contractValueAsset"));
        draft.putNull("instrumentId"); draft.put("symbol", "QA-DELIVERY-EXPIRY-" + run.toUpperCase(Locale.ROOT));
        draft.put("status", "DRAFT");
        var expiry = Instant.now().plusSeconds(45);
        draft.put("expiryTime", expiry.toString()); draft.put("deliveryTime", expiry.plusSeconds(5).toString());
        var created = write("instrument-admin", "/upsert?reason=delivery-expiry-qa&expectedChangeId=0", draft, 200);
        int id = created.path("instrumentId").asInt();
        var listed = changeStatus(id, created, "PRE_TRADING", 200);
        var trading = changeStatus(id, listed, "TRADING", 200);
        awaitSync(id, trading.path("lastChangeId").asLong());
        var user = participantFixture(jdbc, "expiry-trader", false);
        var maker = participantFixture(jdbc, "expiry-maker", false);
        String settlementAsset = line == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
        long expectedPnl = line == ProductLine.INVERSE_DELIVERY ? 78_431L : 200_000_000L;
        for (var actor : List.of(user, maker)) fund(actor, settlementAsset, line == ProductLine.INVERSE_DELIVERY ? 1_000_000_000L : 10_000_000_000_000L);
        String expiryStrategy = "qa-expiry-" + run;
        boolean expiryStrategyCreated = false;
        try (var core = SurprisingAeronClient.connect(line, List.of("127.0.0.1"), "127.0.0.1", Duration.ofSeconds(10))) {
            var before = combinedFunds(core, user.id, maker.id);
            user.connect(id);
            place(maker, id, "SELL", "LIMIT", 500_000, "GTC", false, 2);
            place(user, id, "BUY", "LIMIT", 500_000, "IOC", false, 2);
            var resting = place(user, id, "BUY", "LIMIT", 490_000, "GTC", false, 1);
            assertThat(userState(core, user.id).positions()).anyMatch(p -> p.signedQuantitySteps() == 2);
            var legs = new ArrayList<Map<String, Object>>();
            for (boolean tp : new boolean[]{true, false}) {
                var leg = new LinkedHashMap<String, Object>();
                leg.put("userId", user.id); leg.put("clientTriggerOrderId", "expiry-" + run + (tp ? "-tp" : "-sl"));
                leg.put("ocoGroupId", "expiry-" + run); leg.put("instrumentId", String.valueOf(id));
                leg.put("side", "SELL"); leg.put("triggerType", tp ? "TAKE_PROFIT" : "STOP_LOSS");
                leg.put("triggerPriceTicks", tp ? 600_000 : 400_000); leg.put("orderType", "MARKET");
                leg.put("timeInForce", "IOC"); leg.put("priceTicks", 0); leg.put("quantitySteps", 2);
                leg.put("marginMode", "CROSS"); leg.put("positionSide", "NET"); leg.put("reduceOnly", true);
                legs.add(leg);
            }
            var stops = request("POST", "/api/v1/gateway/trading-trigger/batch", json.writeValueAsString(Map.of("orders", legs, "atomic", true)), user.token, null, 200);
            assertThat(stops.path("completed").asInt()).isEqualTo(2);
            var definition = new LinkedHashMap<String, Object>();
            definition.put("strategyId", expiryStrategy); definition.put("productLine", line.name());
            definition.put("enabled", true); definition.put("accountIds", List.of(maker.id));
            definition.put("instrumentIds", List.of(String.valueOf(id))); definition.put("baseQuantitySteps", 1);
            definition.put("marginMode", "CROSS"); definition.put("spreadTicks", 20); definition.put("levelSpacingTicks", 10);
            definition.put("maxInventorySteps", 1_000_000); definition.put("maxInventorySkewPpm", 0);
            definition.put("orderLevels", 1); definition.put("initialAnchorPriceTicks", 500_000); definition.put("version", 0);
            write("market-maker", "/strategy-definitions?reason=delivery-expiry-qa", definition, 200);
            expiryStrategyCreated = true;
            long quoteDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (userState(core, maker.id).reservations().size() < 2) {
                assertThat(System.nanoTime()).as("live maker quotes before expiry").isLessThan(quoteDeadline);
                Thread.sleep(100);
            }
            write("instrument-admin", "/" + id + "/settlement?productLine=" + line + "&settlementPriceTicks=510000&reason=too-early", null, 409);
            var repository = new InstrumentRepository(jdbc);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
            for (;;) {
                var current = repository.current(id, line).orElseThrow();
                if (current.status() == com.surprising.instrument.api.model.InstrumentStatus.SETTLING) break;
                assertThat(System.nanoTime()).as("automatic expiry to SETTLING").isLessThan(deadline);
                Thread.sleep(200);
            }
            var current = repository.current(id, line).orElseThrow();
            awaitSync(id, current.lastChangeId());
            var readiness = new InstrumentLifecycleReadinessService(new InstrumentLifecycleDrainRepository(jdbc));
            while (!readiness.isReady(line, String.valueOf(id), current.lastChangeId()) || Instant.now().isBefore(expiry.plusSeconds(5))) {
                assertThat(System.nanoTime()).as("delivery time and committed ORDER drain acknowledgement").isLessThan(deadline);
                Thread.sleep(200);
            }
            JsonNode canceled;
            do {
                canceled = request("GET", "/api/v1/gateway/trading/" + resting.path("orderId").asLong() + "?userId=" + user.id, "", user.token, null, 200, 404, 503);
                if ("CANCELED".equals(canceled.path("status").asString())) break;
                assertThat(System.nanoTime()).as("committed expiry cancellation projection").isLessThan(deadline);
                Thread.sleep(100);
            } while (true);
            assertThat(canceled.path("status").asString()).isEqualTo("CANCELED");
            for (var stop : stops.path("results")) {
                long triggerId = stop.path("order").path("triggerOrderId").asLong();
                var retained = CoreTriggerOrderCodec.decodeList(query(core, CoreMessageType.TRIGGER_ORDER_QUERY, user.id,
                        CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(triggerId, "", 0, 1))).data());
                assertThat(retained).hasSize(1);
                assertThat(retained.getFirst().status().name()).isEqualTo("CANCELED");
            }
            var closed = write("instrument-admin", "/" + id + "/settlement?productLine=" + line + "&settlementPriceTicks=510000&reason=expiry-qa", null, 200);
            assertThat(closed.path("status").asString()).isEqualTo("CLOSED");
            for (var actor : List.of(user, maker)) {
                for (;;) {
                    var state = userState(core, actor.id);
                    if (state.positions().stream().allMatch(p -> p.signedQuantitySteps() == 0 && p.positionMarginUnits() == 0)
                            && state.reservations().isEmpty() && state.balances().stream().allMatch(b -> b.lockedUnits() == 0)) break;
                    assertThat(System.nanoTime()).as("committed expiry settlement for " + actor.id).isLessThan(deadline);
                    Thread.sleep(100);
                }
            }
            assertThat(combinedFunds(core, user.id, maker.id)).as("delivery settlement funds conservation").isEqualTo(before);
            var again = write("instrument-admin", "/" + id + "/settlement?productLine=" + line + "&settlementPriceTicks=510000&reason=expiry-retry", null, 200);
            assertThat(again.path("lastChangeId").asLong()).isEqualTo(closed.path("lastChangeId").asLong());
            JsonNode ledger;
            long ledgerDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            do {
                ledger = get("/api/v1/gateway/account/product-ledger?userId=" + user.id + "&accountType=" + line.accountTypeCode() + "&referenceType=DELIVERY_SETTLEMENT&limit=100", user.token);
                if (!ledger.path("entries").isEmpty() || System.nanoTime() >= ledgerDeadline) break;
                Thread.sleep(100);
            } while (true);
            assertThat(ledger.path("entries")).as("real user delivery settlement history " + ledger).hasSize(1);
            assertThat(ledger.path("entries").get(0).path("amountUnits").asLong()).isEqualTo(expectedPnl);
            assertThat(ledger.path("entries").get(0).path("instrumentId").asString()).isEqualTo(String.valueOf(id));
            var makerLedger = get("/api/v1/gateway/account/product-ledger?userId=" + maker.id + "&accountType=" + line.accountTypeCode() + "&referenceType=DELIVERY_SETTLEMENT&limit=100", maker.token);
            assertThat(makerLedger.path("entries")).hasSize(1);
            assertThat(makerLedger.path("entries").get(0).path("amountUnits").asLong()).isEqualTo(-expectedPnl);
            System.out.printf("%s_EXPIRY_PASS instrumentId=%d userId=%d makerId=%d expiry=%s status=CLOSED pnl=%d canceled=1 stopsCanceled=2 makerQuotes=true positions=0 locked=0 fundsDifference=0%n", line, id, user.id, maker.id, expiry, expectedPnl);
        } finally {
            if (expiryStrategyCreated) write("market-maker", "/strategies/" + expiryStrategy + "/pause?productLine=" + line, null, 200);
            if (user.socket != null) user.socket.sendClose(WebSocket.NORMAL_CLOSURE, "expiry QA complete").join();
        }
    }

    private record OptionExpiryCase(int id, Participant buyer, Participant maker, String type, String money, long cash) {}

    private void exerciseOptionExpiryMatrix(JdbcTemplate jdbc, JsonNode template) throws Exception {
        var cases = new ArrayList<OptionExpiryCase>();
        var strategies = new ArrayList<String>();
        var expiry = Instant.now().plusSeconds(120);
        try (var core = SurprisingAeronClient.connect(line, List.of("127.0.0.1"),"127.0.0.1", Duration.ofSeconds(10))) {
            for (String type : List.of("CALL","PUT")) for (String money : List.of("ITM","ATM","OTM")) {
                long strike = money.equals("ATM")?52_000:(type.equals("CALL") == money.equals("ITM")?50_000 : 54_000);
                var draft = ((ObjectNode)template).deepCopy();
                draft.remove(List.of("changeId","lastChangeId","createdAt","updatedAt","baseAsset","quoteAsset","settleAsset","contractValueAsset"));
                draft.putNull("instrumentId");
                draft.put("symbol","QA-OPTION-"+type+'-'+money+'-'+run.toUpperCase(Locale.ROOT));
                draft.put("status","DRAFT");
                draft.put("optionType", type);
                draft.put("strikePriceUnits", strike*100_000_000L);
                draft.put("expiryTime", expiry.toString());
                draft.put("deliveryTime", expiry.plusSeconds(5).toString());
                for (var source : draft.path("indexSources")) {
                    ((ObjectNode)source).put("path","/option?expiry="+expiry+"&premium=5000");
                    ((ObjectNode)source).put("websocketUrl","ws://127.0.0.1:19194/option?expiry="+expiry+"&premium=5000");
                }
                var created = write("instrument-admin","/upsert?reason=option-expiry-qa&expectedChangeId=0", draft, 200);
                int id = created.path("instrumentId").asInt();
                var listed = changeStatus(id, created,"PRE_TRADING", 200);
                var trading = changeStatus(id, listed,"TRADING", 200);
                awaitSync(id, trading.path("lastChangeId").asLong());
                var buyer = participantFixture(jdbc,"option-"+type.toLowerCase(Locale.ROOT)+'-'+money.toLowerCase(Locale.ROOT)+"-buyer", false);
                var maker = participantFixture(jdbc,"option-"+type.toLowerCase(Locale.ROOT)+'-'+money.toLowerCase(Locale.ROOT)+"-maker", false);
                fund(buyer,"USDT", 10_000_000_000_000L);
                fund(maker,"USDT", 10_000_000_000_000L);
                cases.add(new OptionExpiryCase(id, buyer, maker, type, money, money.equals("ITM")?400_000_000L : 0));
            }
            long[] users = cases.stream().flatMapToLong(c -> java.util.stream.LongStream.of(c.buyer.id, c.maker.id)).toArray();
            var before = combinedFunds(core, users);
            for (var c : cases) {
                c.buyer.connect(c.id);
                place(c.maker, c.id,"SELL","LIMIT", 50_000,"GTC", false, 2);
                place(c.buyer, c.id,"BUY","LIMIT", 50_000,"IOC", false, 2);
                assertThat(wallet(core, c.buyer.id)).as("buyer full premium plus fee").isEqualTo(10_000_000_000_000L-1_000_000_000L-500_000L);
                assertThat(wallet(core, c.maker.id)).as("seller premium minus maker fee").isEqualTo(10_000_000_000_000L+1_000_000_000L-200_000L);
                assertThat(userState(core, c.buyer.id).positions()).anyMatch(p -> p.signedQuantitySteps() == 2 && p.positionMarginUnits() == 0);
                assertThat(userState(core, c.maker.id).positions()).anyMatch(p -> p.signedQuantitySteps() == -2 && p.positionMarginUnits()>0);
                place(c.buyer, c.id,"BUY","LIMIT", 49_000,"GTC", false, 1);
                var legs = new ArrayList<Map<String, Object>>();
                for (boolean tp : new boolean[]{true, false}) {
                    var leg = new LinkedHashMap<String, Object>();
                    leg.put("userId", c.buyer.id);
                    leg.put("clientTriggerOrderId","option-"+run+'-'+c.id+(tp?"-tp":"-sl"));
                    leg.put("ocoGroupId","option-"+run+'-'+c.id);
                    leg.put("instrumentId", String.valueOf(c.id));
                    leg.put("side","SELL");
                    leg.put("triggerType", tp?"TAKE_PROFIT":"STOP_LOSS");
                    leg.put("triggerPriceTicks", tp?60_000 : 40_000);
                    leg.put("orderType","MARKET");
                    leg.put("timeInForce","IOC");
                    leg.put("priceTicks", 0);
                    leg.put("quantitySteps", 2);
                    leg.put("marginMode","CROSS");
                    leg.put("positionSide","NET");
                    leg.put("reduceOnly", true);
                    legs.add(leg);
                }
                var stops = request("POST","/api/v1/gateway/trading-trigger/batch", json.writeValueAsString(Map.of("orders", legs,"atomic", true)), c.buyer.token, null, 200);
                assertThat(stops.path("completed").asInt()).isEqualTo(2);
                String strategy = "qa-option-"+run+'-'+c.id;
                var definition = new LinkedHashMap<String, Object>();
                definition.put("strategyId", strategy);
                definition.put("productLine", line.name());
                definition.put("enabled", true);
                definition.put("accountIds", List.of(c.maker.id));
                definition.put("instrumentIds", List.of(String.valueOf(c.id)));
                definition.put("baseQuantitySteps", 1);
                definition.put("marginMode","CROSS");
                definition.put("spreadTicks", 20);
                definition.put("levelSpacingTicks", 10);
                definition.put("maxInventorySteps", 1_000_000);
                definition.put("maxInventorySkewPpm", 0);
                definition.put("orderLevels", 1);
                definition.put("initialAnchorPriceTicks", 50_000);
                definition.put("version", 0);
                write("market-maker","/strategy-definitions?reason=option-expiry-qa", definition, 200);
                strategies.add(strategy);
            }
            var repository = new InstrumentRepository(jdbc);
            var readiness = new InstrumentLifecycleReadinessService(new InstrumentLifecycleDrainRepository(jdbc));
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            for (var c : cases) {
                while (repository.current(c.id, line).orElseThrow().status() != com.surprising.instrument.api.model.InstrumentStatus.SETTLING) {
                    assertThat(System.nanoTime()).as("option clock expiry").isLessThan(deadline);
                    Thread.sleep(200);
                }
                var settling = repository.current(c.id, line).orElseThrow();
                while (!readiness.isReady(line, String.valueOf(c.id), settling.lastChangeId()) || Instant.now().isBefore(expiry.plusSeconds(5))) {
                    assertThat(System.nanoTime()).as("option committed drain readiness").isLessThan(deadline);
                    Thread.sleep(200);
                }
                assertThat(CoreTriggerOrderCodec.decodeList(query(core, CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY, c.buyer.id,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(0, String.valueOf(c.id), 0, 100))).data())).isEmpty();
                String settlement = "/"+c.id+"/settlement?productLine=OPTION&settlementPriceTicks=50000&underlyingSettlementPriceUnits=5200000000000&reason=option-expiry-qa";
                var closed = write("instrument-admin", settlement, null, 200);
                for (var actor : List.of(c.buyer, c.maker)) {
                    for (;;) {
                        var state = userState(core, actor.id);
                        if (state.positions().stream().allMatch(p -> p.signedQuantitySteps() == 0 && p.positionMarginUnits() == 0) && state.reservations().isEmpty() && state.balances().stream().allMatch(b -> b.lockedUnits() == 0)) break;
                        assertThat(System.nanoTime()).as("option final account state").isLessThan(deadline);
                        Thread.sleep(100);
                    }
                }
                assertThat(wallet(core, c.buyer.id)).isEqualTo(10_000_000_000_000L-1_000_000_000L-500_000L+c.cash);
                assertThat(wallet(core, c.maker.id)).isEqualTo(10_000_000_000_000L+1_000_000_000L-200_000L-c.cash);
                assertThat(write("instrument-admin", settlement, null, 200).path("lastChangeId").asLong()).isEqualTo(closed.path("lastChangeId").asLong());
                for (var actor : List.of(c.buyer, c.maker)) {
                    JsonNode ledger = null;
                    long ledgerDeadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    do {ledger = get("/api/v1/gateway/account/product-ledger?userId="+actor.id+"&accountType=OPTION&referenceType=OPTION_EXERCISE&limit=100", actor.token);
                        if (c.cash == 0 || !ledger.path("entries").isEmpty()) break;
                        Thread.sleep(100);
                    } while (System.nanoTime()<ledgerDeadline);
                    assertThat(ledger.path("entries")).hasSize(c.cash == 0?0 : 1);
                    if (c.cash != 0) assertThat(ledger.path("entries").get(0).path("amountUnits").asLong()).isEqualTo(actor == c.buyer?c.cash:-c.cash);
                }
                System.out.printf("OPTION_EXPIRY_ROW_PASS instrumentId=%d buyer=%d maker=%d type=%s moneyness=%s exerciseCash=%d positions=0 locked=0%n", c.id, c.buyer.id, c.maker.id, c.type, c.money, c.cash);
            }
            assertThat(combinedFunds(core, users)).as("all option buyers, makers and treasury conservation").isEqualTo(before);
            System.out.println("OPTION_EXPIRY_MATRIX_PASS rows=6 premiumPerPair=1000000000 takerFee=500000 makerFee=200000 fundsDifference=0");
        } finally {
            for (String strategy : strategies) write("market-maker","/strategies/"+strategy+"/pause?productLine=OPTION", null, 200);
            for (var c : cases) if (c.buyer.socket != null)c.buyer.socket.sendClose(WebSocket.NORMAL_CLOSURE,"option QA complete").join();
        }
    }
    private long wallet(SurprisingAeronClient core, long user) {
        return userState(core, user).balances().stream().filter(b -> b.asset().equals("USDT")).mapToLong(b -> Math.addExact(b.availableUnits(), b.lockedUnits())).sum();
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
        boolean strategyCreated = false, strategyPaused = false;
        try (var core = SurprisingAeronClient.connect(line, List.of("127.0.0.1"), "127.0.0.1", Duration.ofSeconds(10))) {
            String previousFixture = System.getenv("SIX_LINE_LIVE_PREVIOUS_UI_FIXTURE");
            if (previousFixture != null) {
                assertThat(line).isEqualTo(ProductLine.OPTION);
                assertThat(previousFixture).startsWith("/tmp/surprising-option-fused-");
                var previous = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(previousFixture)));
                long previousUser = previous.path("userId").asLong(), previousMaker = previous.path("makerId").asLong();
                for (long actor : new long[]{previousUser, previousMaker}) {
                    var state = userState(core, actor);
                    assertThat(state.positions()).allMatch(p -> p.signedQuantitySteps() == 0 && p.positionMarginUnits() == 0);
                    assertThat(state.reservations()).isEmpty();
                    assertThat(state.balances()).allMatch(b -> b.lockedUnits() == 0);
                }
                assertThat(combinedFunds(core, previousUser, previousMaker)).containsExactlyEntriesOf(Map.of("USDT", 20_000_000_000_000L));
                System.out.println("OPTION_PREVIOUS_QA_FUNDS_PASS positions=0 reservations=0 locked=0 fundsDifference=0");
            }
            var before = combinedFunds(core, user.id, maker.id);
            user.connect(instrumentId);
            var settings = get("/api/v1/admin/gateway/market-maker/business-settings?productLine=" + line, admin);
            var business = ((ObjectNode) settings.path("settings")).deepCopy();
            ((ObjectNode) business.path("engine")).put("enabled", true);
            ((ObjectNode) business.path("trade")).put("enabled", false);
            if ("true".equals(System.getenv("SIX_LINE_LIVE_TRIGGER_READ_VIEW")))
                ((ObjectNode) business.path("trade")).putArray("accountIds").add(maker.id);
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
            strategyCreated = true;
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
            var bought = place(user, instrumentId, "BUY", "MARKET", 0, "IOC", false);
            assertThat(bought.path("status").asString()).isEqualTo("FILLED");
            if (line != ProductLine.SPOT) verifyCurrentRisk(core, user, instrumentId, settlement, instrument);
            if("true".equals(System.getenv("SIX_LINE_LIVE_TRADING_STATISTICS"))) {
                String path="/api/v1/admin/gateway/trading-orders/metrics?productLine="+line+"&windowMinutes=1440&limit=100";
                JsonNode metrics=null;
                for(int attempt=0;attempt<200;attempt++) {
                    metrics=request("GET",path,"",admin,null,200,503);
                    if(metrics.path("positions").path("usersWithPositions").asLong()==2&&metrics.path("orders").path("openOrders").asLong()>0)break;
                    Thread.sleep(100);
                }
                assertThat(metrics.path("productLine").asString()).isEqualTo(line.name());
                assertThat(metrics.path("positions").path("usersWithPositions").asLong()).isEqualTo(2);
                assertThat(metrics.path("positions").path("openPositions").asLong()).isEqualTo(2);
                assertThat(metrics.path("orders").path("openOrders").asLong()).isPositive();
                assertThat(metrics.path("trades").path("trades").asLong()).isEqualTo(1);
                statisticsFixture=Map.of("path",path,"expectedPositions",2,"expectedTrades",1);
                System.out.println("OPTION_STATISTICS_OPEN_PASS globalPositionAccounts=2 makerWithoutPrivateSubscriptionIncluded=true trades=1 openMakerOrders=true");
                awaitClientVerification(user,maker,instrumentId,bid);
            }
            if (line == ProductLine.OPTION && "true".equals(System.getenv("SIX_LINE_LIVE_TRIGGER_READ_VIEW")))
                verifyMakerTriggerHistory(maker, instrumentId);
            var sold = place(user, instrumentId, "SELL", "MARKET", 0, "IOC", line != ProductLine.SPOT);
            assertThat(sold.path("status").asString()).isEqualTo("FILLED");
            if (line == ProductLine.SPOT) {
                for (String side : List.of("BUY", "SELL")) {
                    var fok = place(user, instrumentId, side, "MARKET", 0, "FOK", false);
                    assertThat(fok.path("status").asString()).isEqualTo("FILLED");
                    assertThat(fok.path("orderType").asString()).isEqualTo("MARKET");
                    var limitFok = place(user, instrumentId, side, "LIMIT",
                            side.equals("BUY") ? bid * 101 / 100 : bid * 99 / 100, "FOK", false);
                    assertThat(limitFok.path("status").asString()).isEqualTo("FILLED");
                }
                var insufficient = place(user, instrumentId, "BUY", "LIMIT", bid * 101 / 100, "FOK", false, 1000);
                assertThat(insufficient.path("executedQuantitySteps").asLong()).isZero();
                assertThat(insufficient.path("status").asString()).isIn("CANCELED", "REJECTED");
            }
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
            for (int attempt = 0; attempt < 100 && (user.trades.isEmpty() || user.depth.isEmpty()); attempt++) Thread.sleep(100);
            assertThat(user.depth).as("realtime depth over WebSocket").isNotEmpty();
            assertThat(user.trades).as("realtime public trades over WebSocket").isNotEmpty();
            JsonNode recent = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                recent = get("/api/v1/gateway/candlestick/trades/recent?instrumentId=" + instrumentId + "&limit=50", user.token);
                if (recent.path("trades").size() >= (line == ProductLine.SPOT ? 6 : 2)) break;
                Thread.sleep(100);
            }
            var restTradeIds = new HashSet<String>();
            for (var trade : recent.path("trades")) restTradeIds.add(trade.path("tradeId").asString());
            assertThat(restTradeIds).containsAll(user.trades.keySet());
            if ("true".equals(System.getenv("SIX_LINE_LIVE_USER_FILLS"))) {
                assertThat(line).isEqualTo(ProductLine.SPOT);
                var own = privateFills(user, instrumentId, restTradeIds);
                var makerFills = privateFills(maker, instrumentId, restTradeIds);
                assertThat(own).allMatch(fill -> fill.path("liquidityRole").asString().equals("TAKER"));
                assertThat(makerFills).allMatch(fill -> fill.path("liquidityRole").asString().equals("MAKER"));
                request("GET", "/api/v1/gateway/trading/trades?userId=" + maker.id, "", user.token, null, 403);
                request("GET", "/api/v1/gateway/trading/trades", "", null, null, 401);
                userFillFixture = Map.of("tradeIds", restTradeIds, "count", own.size());
                System.out.printf("SPOT_PRIVATE_FILLS_PASS user=%d maker=%d fills=%d ownOrderSideOnly=true%n", user.id, maker.id, own.size());
            }
            if ("true".equals(System.getenv("SIX_LINE_LIVE_TRADE_READ_VIEW"))) {
                String historyPath = "/api/v1/admin/gateway/trading-orders/trades?productLine=" + line + "&userId=" + user.id + "&instrumentId=" + instrumentId;
                JsonNode history = null;
                for (int attempt = 0; attempt < 100; attempt++) {
                    history = request("GET", historyPath,"",admin,null,200,503);
                    if (history.path("trades").size() == 2) break;
                    Thread.sleep(100);
                }
                assertThat(history.path("trades").size()).as("committed admin execution history " + history).isEqualTo(2);
                var executionTradeIds = new HashSet<String>();
                for (var trade : history.path("trades")) {
                    executionTradeIds.add(trade.path("tradeId").asString());
                    assertThat(trade.path("takerUserId").asLong()).isEqualTo(user.id);
                    assertThat(trade.path("makerUserId").asLong()).isEqualTo(maker.id);
                    assertThat(trade.path("takerOrderId").asLong()).isIn(bought.path("orderId").asLong(),sold.path("orderId").asLong());
                    assertThat(trade.path("quantitySteps").asLong()).isEqualTo(1);
                }
                assertThat(executionTradeIds).containsExactlyInAnyOrderElementsOf(restTradeIds);
                tradeQueryFixture = Map.of("userId",user.id,"makerId",maker.id,"historyPath",historyPath,"tradeIds",executionTradeIds);
                System.out.println("OPTION_ADMIN_TRADES_PASS realMatches=2 userOrderSides=true wsRestAndAuditIdsEqual=true");
            }
            System.out.printf("SIX_LINE_REALTIME_PASS productLine=%s depthEvents=%d wsTrades=%d restTrades=%d identitiesMatch=true%n",
                    line, user.depth.size(), user.trades.size(), restTradeIds.size());
            if ((line == ProductLine.SPOT || line == ProductLine.OPTION)&&statisticsFixture==null) awaitClientVerification(user, maker, instrumentId, bid);
            if (line == ProductLine.OPTION && "true".equals(System.getenv("SIX_LINE_LIVE_OPTION_PROTECTION"))) {
                var directory = java.nio.file.Path.of(System.getenv("SIX_LINE_LIVE_UI_FIXTURE")).getParent();
                for (String client : List.of("web", "client")) {
                    var evidence = json.readTree(java.nio.file.Files.readString(directory.resolve("protection-" + client + "-evidence.json")));
                    var ids = evidence.path("triggerIds");
                    assertThat(ids.size()).isEqualTo(2);
                    for (int i = 0; i < 2; i++) {
                        long triggerId = Long.parseLong(ids.get(i).asString());
                        var retained = CoreTriggerOrderCodec.decodeList(query(core, CoreMessageType.TRIGGER_ORDER_QUERY, user.id,
                                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(triggerId, "", 0, 1))).data()).getFirst();
                        assertThat(retained.instrumentId()).isEqualTo(String.valueOf(instrumentId));
                        assertThat(retained.status()).isEqualTo(client.equals("client") && i == 0
                                ? CoreTriggerOrderStatus.TRIGGERED : CoreTriggerOrderStatus.CANCELED);
                        if (client.equals("client") && i == 0) assertThat(retained.placedOrderId()).isPositive();
                    }
                }
                System.out.println("OPTION_PROTECTION_CORE_PASS webCanceled=2 clientTriggered=1 siblingCanceled=1");
            }
            write("market-maker", "/strategies/" + strategy + "/pause?productLine=" + line, null, 200);
            strategyPaused = true;
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
            verifyPublicOrderRead(user, bought);
            verifyPublicOrderRead(user, sold);
            for (var actor : List.of(user, maker)) {
                var expected = userState(core, actor.id).balances();
                JsonNode balances = null;
                boolean matched = false;
                for (int attempt = 0; attempt < 100; attempt++) {
                    balances = request("GET", "/api/v1/gateway/account/product-balances?userId=" + actor.id
                            + "&accountType=" + line.accountTypeCode(), "", actor.token, null, 200, 503);
                    var rows = new HashMap<String, JsonNode>();
                    for (var row : balances.path("balances")) rows.put(row.path("asset").asString(), row);
                    matched = expected.stream().allMatch(balance -> rows.containsKey(balance.asset())
                            && rows.get(balance.asset()).path("availableUnits").asLong() == balance.availableUnits()
                            && rows.get(balance.asset()).path("lockedUnits").asLong() == balance.lockedUnits());
                    if (matched) break;
                    Thread.sleep(100);
                }
                assertThat(matched).as("public realtime balance agrees with committed core state " + balances).isTrue();
            }
            System.out.printf("SIX_LINE_PUBLIC_QUERY_PASS productLine=%s terminalByOrderAndClient=true balancesMatch=true%n", line);
            System.out.printf("SIX_LINE_TRADING_PASS productLine=%s instrumentId=%d trader=%d maker=%d executions=%d fundsDifference=0%n",
                    line, instrumentId, user.id, maker.id, user.executions.size());
        } finally {
            if (strategyCreated && !strategyPaused)
                write("market-maker", "/strategies/" + strategy + "/pause?productLine=" + line, null, 200);
            if (user.socket != null) user.socket.sendClose(WebSocket.NORMAL_CLOSURE, "QA complete").join();
        }
    }

    private List<JsonNode> privateFills(Participant actor, int instrumentId, Set<String> expected) throws Exception {
        String path = "/api/v1/gateway/trading/trades?instrumentId=" + instrumentId;
        JsonNode ready = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            ready = request("GET", path, "", actor.token, null, 200, 503);
            if (ready.path("trades").size() == expected.size()) break;
            Thread.sleep(100);
        }
        assertThat(ready.path("trades").size()).as("committed private fill view").isEqualTo(expected.size());
        var rows = new ArrayList<JsonNode>();
        String cursor = null;
        do {
            var page = get(path + "&limit=1" + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8)), actor.token);
            assertThat(page.path("userId").asLong()).isEqualTo(actor.id);
            assertThat(page.path("productLine").asString()).isEqualTo(line.name());
            assertThat(page.path("count").asInt()).isEqualTo(1);
            var row = page.path("trades").get(0);
            assertThat(row.path("orderId").asLong()).isPositive();
            assertThat(row.has("takerUserId") || row.has("makerUserId") || row.has("takerOrderId") || row.has("makerOrderId")).isFalse();
            rows.add(row);
            cursor = page.path("hasMore").asBoolean() ? page.path("nextCursor").asString() : null;
            if (cursor != null) assertThat(cursor).isNotBlank();
            assertThat(rows.size()).isLessThanOrEqualTo(expected.size());
        } while (cursor != null);
        assertThat(rows.stream().map(row -> row.path("tradeId").asString()).toList()).containsExactlyInAnyOrderElementsOf(expected);
        return rows;
    }

    private void verifyCurrentRisk(SurprisingAeronClient core, Participant actor, int instrumentId, String settlement, JsonNode instrument) throws Exception {
        var position = userState(core, actor.id).positions().stream().filter(p -> p.instrumentId().equals(String.valueOf(instrumentId))).findFirst().orElseThrow();
        assertThat(position.signedQuantitySteps()).isEqualTo(1);
        JsonNode risk = null, account = null;
        long riskDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        // 两个 GET 可能跨过一次标记价更新；只对齐一致的持仓/账户投影再独立核算。
        while (true) {
            var rows = get("/api/v1/gateway/risk/positions/latest?userId=" + actor.id, actor.token).path("positions");
            risk = null;
            for (var candidate : rows) if (candidate.path("instrumentId").asInt() == instrumentId) risk = candidate;
            account = get("/api/v1/gateway/risk/account/latest?userId=" + actor.id + "&accountType=" + line.accountTypeCode() + "&settleAsset=" + settlement, actor.token);
            if (risk != null && account.path("unrealizedPnlUnits").asLong() == risk.path("unrealizedPnlUnits").asLong()
                    && account.path("maintenanceMarginUnits").asLong() == risk.path("maintenanceMarginUnits").asLong()) break;
            assertThat(System.nanoTime()).as("bounded coherent risk reads: position=" + risk + " account=" + account).isLessThan(riskDeadline);
            Thread.sleep(100);
        }
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

    private void verifyPublicOrderRead(Participant actor, JsonNode placed) throws Exception {
        for (String suffix : List.of("/" + placed.path("orderId").asLong(),
                "/by-client-order-id?clientOrderId=" + placed.path("clientOrderId").asString())) {
            String separator = suffix.contains("?") ? "&" : "?";
            JsonNode result = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                result = request("GET", "/api/v1/gateway/trading" + suffix + separator + "userId=" + actor.id,
                        "", actor.token, null, 200, 404, 503);
                if (result.path("orderId").asLong() == placed.path("orderId").asLong()) break;
                Thread.sleep(100);
            }
            assertThat(result.path("orderId").asLong()).as("terminal query " + result).isEqualTo(placed.path("orderId").asLong());
            assertThat(result.path("status").asString()).isEqualTo("FILLED");
        }
    }

    private void fund(Participant actor, String asset, long units) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("userId", actor.id); body.put("asset", asset); body.put("amountUnits", units);
        body.put("referenceId", "qa-" + run + "-" + actor.id + "-" + asset); body.put("reason", "isolated live QA funding");
        if (line != ProductLine.SPOT) body.put("accountType", line.accountTypeCode());
        // Retry only REQUEST_NOT_ACCEPTED, preserving the approved body and stable Core
        // commandId. The refusal can also concern the post-command balance read, so the
        // same reference is essential; the returned balance must prove one credit.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        for (;;) {
            var response = write("account", line == ProductLine.SPOT ? "/balance-adjustments" : "/product-balance-adjustments", body, 200, 503);
            if (!"REQUEST_NOT_ACCEPTED".equals(response.path("code").asString())) {
                assertThat(response.path("userId").asLong()).isEqualTo(actor.id);
                assertThat(response.path("asset").asString()).isEqualTo(asset);
                assertThat(response.path("availableUnits").asLong()).as("fund once before any trading").isEqualTo(units);
                assertThat(response.path("lockedUnits").asLong()).isZero();
                return;
            }
            System.out.printf("QA_FUND_NOT_ADMITTED product=%s userId=%d referenceId=%s unchanged=true%n", line, actor.id, body.get("referenceId"));
            assertThat(System.nanoTime()).as("bounded REQUEST_NOT_ACCEPTED retry with unchanged financial identity").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private JsonNode place(Participant actor, int instrumentId, String side, String type, long price, String tif, boolean reduceOnly) throws Exception {
        return place(actor, instrumentId, side, type, price, tif, reduceOnly, 1);
    }

    private JsonNode place(Participant actor, int instrumentId, String side, String type, long price, String tif, boolean reduceOnly, long quantity) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("userId", actor.id); body.put("clientOrderId", "qa-" + UUID.randomUUID());
        body.put("instrumentId", String.valueOf(instrumentId)); body.put("side", side); body.put("orderType", type);
        body.put("priceTicks", price); body.put("quantitySteps", quantity); body.put("timeInForce", tif);
        body.put("marginMode", "CROSS"); body.put("positionSide", "NET"); body.put("reduceOnly", reduceOnly); body.put("postOnly", false);
        String serialized = json.writeValueAsString(body);
        JsonNode receipt;
        long admissionDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        for (;;) {
            receipt = request("POST", "/api/v1/gateway/trading", serialized, actor.token, null, 200, 503);
            // A negative offer proves this attempt was not admitted. Keep the original
            // clientOrderId and body; unknown or accepted outcomes must never be resent.
            if (!"NOT_ACCEPTED".equals(receipt.path("outcome").asString())
                    || !Set.of("CLOSED", "NOT_CONNECTED").contains(receipt.path("code").asString())) break;
            System.out.printf("QA_ORDER_NOT_ADMITTED product=%s userId=%d commandId=%s unchanged=true%n",
                    line, actor.id, receipt.path("commandId").asString());
            assertThat(System.nanoTime()).as("bounded negative-offer retry with original order identity").isLessThan(admissionDeadline);
            Thread.sleep(100);
        }
        String resultUrl = receipt.path("commandResultUrl").asString();
        assertThat(resultUrl).startsWith("/api/v1/gateway/trading/commands/");
        JsonNode confirmed = null;
        long receiptDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            confirmed = request("GET", resultUrl, "", actor.token, null, 200, 202);
            if ("TERMINAL".equals(confirmed.path("outcome").asString())) break;
            assertThat(System.nanoTime()).as("committed realtime command projection").isLessThan(receiptDeadline);
            Thread.sleep(100);
        } while (true);
        assertThat(confirmed.path("commandId").asString()).isEqualTo(receipt.path("commandId").asString());
        assertThat(confirmed.path("outcome").asString()).isEqualTo("TERMINAL");
        return orderResult(receipt);
    }

    private JsonNode orderResult(JsonNode receipt) {
        JsonNode order = receipt.has("orderId") ? receipt : receipt.path("result");
        assertThat(order.has("orderId")).as("terminal order response " + receipt).isTrue();
        return order;
    }

    private CoreResponse query(SurprisingAeronClient core, CoreMessageType type, long user) {
        return query(core, type, user, new byte[0]);
    }

    private CoreResponse query(SurprisingAeronClient core, CoreMessageType type, long user, byte[] payload) {
        long now = System.currentTimeMillis();
        var response = core.submit(new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), line,
                CommandSource.OPERATIONS, 0, now, user, now, now), payload));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return response;
    }

    private CoreUserStateView userState(SurprisingAeronClient core, long user) {
        return CoreStateQueryCodec.decodeUserState(query(core, CoreMessageType.USER_STATE_QUERY, user).data());
    }

    private Map<String, Long> combinedFunds(SurprisingAeronClient core, long... users) {
        var funds = new TreeMap<String, Long>();
        for (long actor : users) for (var balance : userState(core, actor).balances())
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
        final Map<String, JsonNode> trades = new ConcurrentHashMap<>();
        final List<JsonNode> depth = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final StringBuilder fragments = new StringBuilder();
        WebSocket socket;
        Participant(long id, String token) { this.id = id; this.token = token; }
        void connect(int instrumentId) {
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://127.0.0.1:9094/ws/v1"), this).join();
            socket.sendText(json.writeValueAsString(Map.of("op", "authenticate", "id", "auth", "token", token)), true).join();
            subscribed.orTimeout(15, TimeUnit.SECONDS).join();
            for (String channel : List.of("trades", "depth")) socket.sendText(json.writeValueAsString(Map.of(
                    "op", "subscribe", "id", channel, "channel", channel, "productLine", line.name(),
                    "instrumentId", String.valueOf(instrumentId))), true).join();
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
                if (op.equals("event") && event.path("channel").asString().equals("depth")) depth.add(event);
                if (op.equals("event") && event.path("channel").asString().equals("trades")) {
                    var trade = event.path("data").path("value");
                    assertThat(trade.path("tradeId").asString()).isNotBlank();
                    assertThat(trade.path("instrumentId").asString()).isEqualTo(event.path("instrumentId").asString());
                    assertThat(trade.path("eventTime").asString()).isNotBlank();
                    trades.put(trade.path("tradeId").asString(), trade);
                }
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

    private JsonNode write(String service, String suffix, Object body, int... expected) throws Exception {
        String path = "/api/v1/admin/gateway/" + service + suffix;
        String serialized = body == null ? "" : json.writeValueAsString(body);
        boolean settlementConfirmation = service.equals("instrument-admin") && path.contains("/settlement?");
        if (!service.equals("account") && !settlementConfirmation) {
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
        String runId = System.getenv().getOrDefault("SIX_LINE_LIVE_RUN_ID", "surprising-six-lines-20261005");
        ProcessHandle.allProcesses().filter(p -> p.info().commandLine().orElse("").contains(runId)
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
