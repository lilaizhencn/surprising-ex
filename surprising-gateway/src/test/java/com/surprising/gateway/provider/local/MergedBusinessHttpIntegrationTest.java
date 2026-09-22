package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.*;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Requires an isolated, seeded business application, independent Core and Kafka; never runs against production. */
@EnabledIfEnvironmentVariable(named = "MERGED_BUSINESS_IT_BASE_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class MergedBusinessHttpIntegrationTest {
    private static final String SYMBOL = "BTC-USDT-SWAP";
    private static final long PRICE = 100_000;
    private static final long DEPOSIT = 1_000_000_000_000_000L;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String base = System.getenv("MERGED_BUSINESS_IT_BASE_URL");

    @Test
    void identityOrdersAccountsAndInstrumentsRunInOneAppWhileCoreRemainsIndependent() throws Exception {
        assertThat(get("/actuator/health/liveness", null).get("status").asText()).isEqualTo("UP");
        var instrument = get("/api/v1/gateway/instrument/latest?symbol=" + SYMBOL, null);
        long changeId = Long.parseLong(instrument.get("changeId").asText());
        long priceUnits = PRICE * Long.parseLong(instrument.get("priceTickUnits").asText());
        User maker = register("maker");
        User user = register("user");
        assertThat(send("GET", "/api/v1/accounts/balance?userId=" + user.id() + "&asset=USDT", user.token(), null).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", "/api/v1/gateway/account/balance?userId=" + maker.id() + "&asset=USDT", user.token(), null).statusCode())
                .isEqualTo(403);
        assertThat(send("GET", "/api/v1/gateway/account/balance?asset=USDT&productLine=SPOT", user.token(), null).statusCode())
                .isEqualTo(404);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Objects.requireNonNull(System.getenv("MERGED_BUSINESS_IT_KAFKA")));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        try (var core = new AeronClientPool("business-merge-it", ProductLine.LINEAR_PERPETUAL,
                List.of("127.0.0.1"), "127.0.0.1", Duration.ofSeconds(5), 1);
             var prices = new KafkaProducer<String, String>(props);
             var feed = Executors.newSingleThreadScheduledExecutor()) {
            for (User actor : List.of(maker, user)) {
                var response = core.command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), actor.id(),
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", DEPOSIT)));
                assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            var mark = core.command(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(SYMBOL, PRICE,
                            System.currentTimeMillis(), System.currentTimeMillis())));
            assertThat(mark.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            feed.scheduleAtFixedRate(() -> {
                try {
                    prices.send(new ProducerRecord<>("surprising.linear-perp.price.events.v1", SYMBOL,
                            priceEvent(changeId, priceUnits))).get(5, TimeUnit.SECONDS);
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                }
            }, 0, 200, TimeUnit.MILLISECONDS);
            // Cache readiness is an explicit bounded prerequisite, not a retry of an order with an unknown outcome.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                var check = send("POST", "/api/v1/gateway/trading/test", user.token(), order(user, "BUY", PRICE, false));
                if (check.statusCode() == 200 && json.readTree(check.body()).path("accepted").asBoolean()) break;
                if (System.nanoTime() >= deadline) throw new AssertionError("mark-price readiness: " + check.body(), failure.get());
                Thread.sleep(100);
            }
            place(maker, "SELL", PRICE, false);
            place(user, "BUY", PRICE, false);
            assertThat(position(maker)).isEqualTo(-1);
            assertThat(position(user)).isEqualTo(1);
            var resting = place(user, "BUY", PRICE - 100, false);
            long orderId = Long.parseLong(resting.get("prospectiveOrderIds").get(0).asText());
            command("/api/v1/gateway/trading/cancel", user,
                    Map.of("userId", user.id(), "orderId", orderId, "symbol", SYMBOL));
            // The maker remains active and provides the closing liquidity.
            place(maker, "BUY", PRICE, true);
            place(user, "SELL", PRICE, true);
            assertThat(position(maker)).isZero();
            assertThat(position(user)).isZero();
            var makerBalance = get("/api/v1/gateway/account/balance?asset=USDT", maker.token());
            var userBalance = get("/api/v1/gateway/account/balance?asset=USDT", user.token());
            assertThat(Long.parseLong(makerBalance.get("lockedUnits").asText())).isZero();
            assertThat(Long.parseLong(userBalance.get("lockedUnits").asText())).isZero();
            long notional = PRICE * Long.parseLong(instrument.get("notionalMultiplierUnits").asText());
            long fees = 2 * (notional * Long.parseLong(instrument.get("makerFeeRatePpm").asText()) / 1_000_000
                    + notional * Long.parseLong(instrument.get("takerFeeRatePpm").asText()) / 1_000_000);
            assertThat(Long.parseLong(makerBalance.get("equityUnits").asText()) + Long.parseLong(userBalance.get("equityUnits").asText()) + fees)
                    .isEqualTo(2 * DEPOSIT);
            assertThat(failure.get()).isNull();
            feed.shutdownNow();
        }
    }

    private JsonNode place(User actor, String side, long price, boolean reduceOnly) throws Exception {
        return command("/api/v1/gateway/trading", actor, order(actor, side, price, reduceOnly));
    }

    private Map<String, Object> order(User actor, String side, long price, boolean reduceOnly) {
        return Map.of("userId", actor.id(), "clientOrderId", "merge-" + UUID.randomUUID(), "symbol", SYMBOL,
                "side", side, "orderType", "LIMIT", "timeInForce", "GTC", "priceTicks", price,
                "quantitySteps", 1, "reduceOnly", reduceOnly, "postOnly", false);
    }

    private JsonNode command(String path, User user, Object body) throws Exception {
        var response = send("POST", path, user.token(), body);
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        var receipt = json.readTree(response.body());
        assertThat(receipt.get("commandResultUrl").asText()).startsWith("/api/v1/gateway/trading/commands/");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (receipt.get("result") == null || receipt.get("result").isNull()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("command did not reach terminal: " + receipt);
            Thread.sleep(20);
            receipt = get("/api/v1/gateway/trading/commands/" + receipt.get("commandId").asText(), user.token());
        }
        assertThat(receipt.get("code").asText()).as(receipt.toString()).isEqualTo("NONE");
        return receipt;
    }

    private long position(User actor) throws Exception {
        return Long.parseLong(get("/api/v1/gateway/account/position?symbol=" + SYMBOL, actor.token()).get("signedQuantitySteps").asText());
    }

    private User register(String name) throws Exception {
        var response = send("POST", "/api/v1/auth/register", null,
                Map.of("email", name + "-" + UUID.randomUUID() + "@merge-test.example", "password", "Merge-test-password-42!"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var result = json.readTree(response.body());
        return new User(Long.parseLong(result.get("user").get("userId").asText()), result.get("accessToken").asText());
    }

    private JsonNode get(String path, String token) throws Exception {
        var result = send("GET", path, token, null);
        assertThat(result.statusCode()).as(result.body()).isBetween(200, 299);
        return json.readTree(result.body());
    }

    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String priceEvent(long changeId, long units) {
        var root = json.createObjectNode();
        String now = Instant.now().toString();
        root.put("schemaVersion", 1).put("eventType", "MARK_PRICE").put("symbol", SYMBOL).put("generatedAt", now);
        var mark = root.putObject("markPrice");
        mark.put("calculatedAt", now).put("basisWindowSeconds", 1);
        var result = mark.putObject("result");
        result.put("productLine", "LINEAR_PERPETUAL").put("symbol", SYMBOL).put("instrumentChangeId", changeId)
                .put("markPriceUnits", units).put("markPriceTicks", PRICE).put("sequence", System.currentTimeMillis())
                .put("timeUntilFundingSeconds", 1).put("basisWindowSeconds", 1)
                .put("status", "HEALTHY").put("eventTime", now).put("publishedAt", now).put("markPrice", 1).put("indexPrice", 1);
        var index = mark.putObject("indexInput");
        index.put("symbol", SYMBOL).put("indexPrice", 1).put("eventTime", now).put("status", "HEALTHY");
        index.put("sequence", System.currentTimeMillis()).put("componentCount", 1).put("validComponentCount", 1);
        index.putArray("components");
        return json.writeValueAsString(root);
    }

    private record User(long id, String token) {}
}
