package com.surprising.gateway.provider.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Opt-in real HTTP/WS verification against the dedicated local LINEAR_PERPETUAL runtime. */
@EnabledIfEnvironmentVariable(named = "SURPRISING_LIVE_QA", matches = "true")
class LocalTradingLifecycleLiveTest {
    private static final String BASE = "http://127.0.0.1:9094";
    private static final ProductLine PRODUCT = ProductLine.LINEAR_PERPETUAL;
    private static final long DEPOSIT = 100_000_000_000L;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String run = "qa-" + System.currentTimeMillis();
    private final Path output = Path.of("target", "live-qa", run);
    private final List<Participant> participants = new CopyOnWriteArrayList<>();
    private final AtomicInteger completed = new AtomicInteger();
    private final Map<String, List<Long>> httpLatencyMicros = new ConcurrentHashMap<>();

    @Test
    void usersTradeAgainstLiveMakersAndReconcileFromExecutions() throws Exception {
        Files.createDirectories(output);
        int count = Integer.getInteger("live.users", 100);
        int cycles = Integer.getInteger("live.cycles", 3);
        JsonNode makerBefore = request("GET", "http://127.0.0.1:9096/api/v1/market-maker/strategies", null, null);
        Files.writeString(output.resolve("maker-before.json"), json.writeValueAsString(makerBefore));
        List<String> symbols = new ArrayList<>();
        for (JsonNode strategy : makerBefore.path("strategies")) {
            assertThat(strategy.path("status").asText()).isEqualTo("RUNNING");
            symbols.add(strategy.path("symbols").get(0).asText());
        }
        assertThat(symbols).hasSize(20);
        Map<String, JsonNode> instruments = new HashMap<>();
        for (String symbol : symbols) instruments.put(symbol, request("GET", BASE + "/api/v1/gateway/instrument/latest?symbol=" + symbol, null, null));
        try (var executor = Executors.newFixedThreadPool(Integer.getInteger("live.concurrency", 10))) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                tasks.add(executor.submit(() -> {
                    Participant user = register(index);
                    participants.add(user);
                    adjust(user, DEPOSIT, "deposit");
                    user.connect();
                    String symbol = symbols.get(index % symbols.size());
                    for (int cycle = 0; cycle < cycles; cycle++) exercise(user, symbol, instruments.get(symbol), cycle);
                    System.out.println("live-qa completed=" + completed.incrementAndGet() + "/" + count + " user=" + user.id);
                }));
            }
            for (Future<?> task : tasks) task.get(15, TimeUnit.MINUTES);
        } finally {
            Files.writeString(output.resolve("http-latency.json"), json.writeValueAsString(latencySummary()));
            // Persist identities without access tokens so an interrupted run can be reconciled.
            Files.writeString(output.resolve("users.json"), json.writeValueAsString(participants.stream().map(p -> Map.of("userId", p.id, "email", p.email, "orders", p.orders.values())).toList()));
            for (Participant p : participants) {
                Files.writeString(output.resolve("executions-" + p.id + ".json"), json.writeValueAsString(p.executions.values()));
                Files.writeString(output.resolve("errors-" + p.id + ".json"), json.writeValueAsString(p.errors));
            }
        }
        Thread.sleep(5000);
        for (Participant p : participants) Files.writeString(output.resolve("executions-" + p.id + ".json"), json.writeValueAsString(p.executions.values()));
        List<Map<String, Object>> balances = new ArrayList<>();
        try (var core = SurprisingAeronClient.connect(PRODUCT, List.of("127.0.0.1"), "127.0.0.1", Duration.ofSeconds(10))) {
            for (Participant user : participants) {
                CoreUserStateView state = CoreStateQueryCodec.decodeUserState(query(core, CoreMessageType.USER_STATE_QUERY, user.id, new byte[0]).data());
                assertThat(state.reservations()).as("reservations user=" + user.id).isEmpty();
                assertThat(state.positions()).allMatch(p -> p.signedQuantitySteps() == 0 && p.positionMarginUnits() == 0);
                BigInteger netNotional = BigInteger.ZERO, fees = BigInteger.ZERO;
                Map<Long, Long> executed = new HashMap<>();
                for (JsonNode event : user.executions.values()) {
                    JsonNode fill = event.path("data").path("value");
                    long orderId = fill.path("orderId").asLong();
                    JsonNode order = user.orders.get(orderId);
                    assertThat(order).as("execution belongs to submitted order").isNotNull();
                    assertThat(event.path("symbol").asText()).isEqualTo(order.path("symbol").asText());
                    JsonNode instrument = instruments.get(order.path("symbol").asText());
                    BigInteger notional = BigInteger.valueOf(fill.path("priceTicks").asLong())
                            .multiply(BigInteger.valueOf(fill.path("quantitySteps").asLong()))
                            .multiply(BigInteger.valueOf(instrument.path("notionalMultiplierUnits").asLong()));
                    netNotional = netNotional.add("SELL".equals(fill.path("side").asText()) ? notional : notional.negate());
                    long rate = order.path(fill.path("maker").asBoolean() ? "makerFeeRatePpm" : "takerFeeRatePpm").asLong();
                    BigInteger fee = notional.multiply(BigInteger.valueOf(Math.abs(rate))).add(BigInteger.valueOf(999999)).divide(BigInteger.valueOf(1000000));
                    fees = fees.add(rate < 0 ? fee.negate() : fee);
                    executed.merge(orderId, fill.path("quantitySteps").asLong(), Math::addExact);
                }
                for (JsonNode order : user.orders.values()) {
                    assertThat(executed.getOrDefault(order.path("orderId").asLong(), 0L)).as("all fills arrived user=" + user.id)
                            .isEqualTo(order.path("executedQuantitySteps").asLong());
                }
                var balance = state.balances().stream().filter(b -> b.asset().equals("USDT")).findFirst().orElseThrow();
                BigInteger expected = BigInteger.valueOf(DEPOSIT).add(netNotional).subtract(fees);
                assertThat(balance.availableUnits()).as("independent cashflow reconciliation user=" + user.id).isEqualTo(expected.longValueExact());
                assertThat(balance.lockedUnits()).isZero();
                assertThat(user.errors).isEmpty();
                balances.add(Map.of("userId", user.id, "balanceUnits", balance.availableUnits(), "realizedPnlUnits", netNotional.toString(), "feeUnits", fees.toString(), "executions", user.executions.size(), "fundsDifference", 0));
                user.ws.sendClose(WebSocket.NORMAL_CLOSURE, "verified").join();
            }
        }
        JsonNode makerAfter = request("GET", "http://127.0.0.1:9096/api/v1/market-maker/strategies", null, null);
        for (JsonNode strategy : makerAfter.path("strategies")) assertThat(strategy.path("status").asText()).isEqualTo("RUNNING");
        Files.writeString(output.resolve("report.json"), json.writeValueAsString(Map.of("run", run, "users", count, "cycles", cycles, "balances", balances, "makerBefore", makerBefore, "makerAfter", makerAfter, "httpLatencyMicros", latencySummary())));
        System.out.println("live-qa PASS report=" + output.toAbsolutePath());
    }

    private void exercise(Participant user, String symbol, JsonNode instrument, int cycle) {
        long quantity = Math.max(1, instrument.path("minQuantitySteps").asLong());
        JsonNode book = request("GET", BASE + "/api/v1/gateway/trading-market/orderbook?symbol=" + symbol + "&depth=50", null, user.token);
        long bestBid = book.path("bids").get(0).path("priceTicks").asLong();
        JsonNode resting = place(user, symbol, "BUY", false, "LIMIT", Math.max(1, bestBid * 995 / 1000), quantity, true);
        assertThat(resting.path("status").asText()).isEqualTo("ACCEPTED");
        JsonNode canceled = orderResult(request("POST", BASE + "/api/v1/gateway/trading/cancel", Map.of("userId", user.id, "orderId", resting.path("orderId").asLong()), user.token));
        assertThat(canceled.path("status").asText()).isEqualTo("CANCELED");
        user.orders.put(canceled.path("orderId").asLong(), canceled);
        for (String side : List.of("BUY", "SELL")) {
            JsonNode opened = place(user, symbol, side, false, "MARKET", 0, quantity, false);
            assertThat(opened.path("status").asText()).as("open " + symbol).isEqualTo("FILLED");
            JsonNode closed = place(user, symbol, side.equals("BUY") ? "SELL" : "BUY", true, "MARKET", 0, quantity, false);
            assertThat(closed.path("status").asText()).as("close " + symbol).isEqualTo("FILLED");
        }
    }

    @Test
    void atomicOcoPairTriggersEitherLegThroughRealApi() throws Exception {
        Files.createDirectories(output);
        Participant user = register(200);
        Files.writeString(output.resolve("users.json"), json.writeValueAsString(
                List.of(Map.of("userId", user.id, "email", user.email))));
        adjust(user, DEPOSIT, "oco-deposit");
        user.connect();
        var evidence = new ArrayList<Map<String, Object>>();
        String symbol = "BTC-USDT-SWAP";
        var instrument = request("GET", BASE + "/api/v1/gateway/instrument/latest?symbol=" + symbol, null, null);
        try (var core = SurprisingAeronClient.connect(PRODUCT, List.of("127.0.0.1"), "127.0.0.1", Duration.ofSeconds(10))) {
            for (String openingSide : List.of("BUY", "SELL")) {
            for (boolean takeProfitWins : new boolean[]{true, false}) {
                var opened = place(user, symbol, openingSide, false, "MARKET", 0, 1, false);
                assertThat(opened.path("status").asText()).isEqualTo("FILLED");
                var mark = request("GET", BASE + "/api/v1/gateway/price-mark/latest?symbol=" + symbol, null, null);
                long markTicks = mark.path("markPriceUnits").asLong() / instrument.path("priceTickUnits").asLong();
                long direction = openingSide.equals("BUY") ? 1 : -1;
                String group = run + "-oco-" + openingSide + "-" + takeProfitWins;
                List<Map<String, Object>> legs = new ArrayList<>();
                for (boolean tp : new boolean[]{true, false}) {
                    var leg = new LinkedHashMap<String, Object>();
                    leg.put("userId", user.id); leg.put("clientTriggerOrderId", group + (tp ? "-tp" : "-sl"));
                    leg.put("ocoGroupId", group); leg.put("symbol", symbol); leg.put("side", openingSide.equals("BUY") ? "SELL" : "BUY");
                    leg.put("triggerType", tp ? "TAKE_PROFIT" : "STOP_LOSS");
                    leg.put("triggerPriceTicks", tp ? markTicks + direction * (takeProfitWins ? -10_000 : 100_000)
                            : markTicks + direction * (takeProfitWins ? -100_000 : 10_000));
                    leg.put("orderType", "MARKET"); leg.put("timeInForce", "IOC"); leg.put("priceTicks", 0);
                    leg.put("quantitySteps", 1); leg.put("marginMode", "CROSS"); leg.put("positionSide", "NET");
                    legs.add(leg);
                }
                var accepted = request("POST", BASE + "/api/v1/gateway/trading-trigger/batch",
                        Map.of("orders", legs, "atomic", true), user.token);
                assertThat(accepted.path("completed").asInt()).isEqualTo(2);
                long winner = accepted.path("results").get(takeProfitWins ? 0 : 1).path("order").path("triggerOrderId").asLong();
                long sibling = accepted.path("results").get(takeProfitWins ? 1 : 0).path("order").path("triggerOrderId").asLong();
                CoreTriggerOrderStateView terminal = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                do {
                    terminal = CoreTriggerOrderCodec.decodeList(query(core, CoreMessageType.TRIGGER_ORDER_QUERY, user.id,
                            CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(winner, "", 0, 1))).data()).getFirst();
                    if (!terminal.status().open()) break;
                    Thread.sleep(100);
                } while (System.nanoTime() < deadline);
                assertThat(terminal.status()).as("winning leg " + takeProfitWins).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
                var canceled = CoreTriggerOrderCodec.decodeList(query(core, CoreMessageType.TRIGGER_ORDER_QUERY, user.id,
                        CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(sibling, "", 0, 1))).data()).getFirst();
                assertThat(canceled.status()).isEqualTo(CoreTriggerOrderStatus.CANCELED);
                var state = CoreStateQueryCodec.decodeUserState(query(core, CoreMessageType.USER_STATE_QUERY, user.id, new byte[0]).data());
                assertThat(state.positions()).allMatch(position -> position.signedQuantitySteps() == 0 && position.positionMarginUnits() == 0);
                assertThat(state.reservations()).isEmpty();
                evidence.add(Map.of("userId", user.id, "winner", terminal, "sibling", canceled,
                        "balances", state.balances(), "positions", state.positions(), "openingSide", openingSide));
            }
            }
        } finally {
            Files.writeString(output.resolve("oco.json"), json.writeValueAsString(evidence));
            user.ws.sendClose(WebSocket.NORMAL_CLOSURE, "complete").join();
        }
    }

    private JsonNode place(Participant user, String symbol, String side, boolean reduce, String type, long price, long qty, boolean postOnly) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.id); body.put("clientOrderId", run + "-" + UUID.randomUUID().toString().substring(0, 20));
        body.put("symbol", symbol); body.put("side", side); body.put("orderType", type);
        body.put("timeInForce", postOnly ? "GTX" : "IOC"); body.put("priceTicks", price); body.put("quantitySteps", qty);
        body.put("marginMode", "CROSS"); body.put("positionSide", "NET"); body.put("reduceOnly", reduce); body.put("postOnly", postOnly);
        JsonNode order = orderResult(request("POST", BASE + "/api/v1/gateway/trading", body, user.token));
        user.orders.put(order.path("orderId").asLong(), order);
        return order;
    }

    private JsonNode orderResult(JsonNode receipt) {
        if (receipt.has("orderId")) return receipt;
        JsonNode result = receipt.path("result");
        if (result.has("orderId")) return result;
        throw new AssertionError("order command not terminal: " + receipt);
    }

    private Participant register(int index) {
        String email = run + "-" + index + "@surprising.test";
        JsonNode auth = request("POST", BASE + "/api/v1/auth/register", Map.of("email", email, "password", "LocalQA#2026-09-26!"), null);
        return new Participant(auth.path("user").path("userId").asLong(), email, auth.path("accessToken").asText());
    }

    private void adjust(Participant user, long units, String suffix) {
        JsonNode balance = request("POST", BASE + "/api/v1/accounts/admin/product-balance-adjustments", Map.of("userId", user.id, "accountType", "USDT_PERPETUAL", "asset", "USDT", "amountUnits", units, "referenceId", run + "-" + user.id + "-" + suffix, "reason", "local API lifecycle verification"), null);
        assertThat(balance.path("availableUnits").asLong()).isEqualTo(units);
    }

    private JsonNode request(String method, String url, Object body, String token) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("X-Product-Line", PRODUCT.name());
            if (token != null) builder.header("Authorization", "Bearer " + token);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            long started = System.nanoTime();
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            httpLatencyMicros.computeIfAbsent(method + " " + URI.create(url).getPath(),
                    ignored -> Collections.synchronizedList(new ArrayList<>()))
                    .add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new AssertionError(method + " " + url + " status=" + response.statusCode() + " " + response.body());
            return json.readTree(response.body());
        } catch (Exception ex) { throw new IllegalStateException(method + " " + url, ex); }
    }

    private Map<String, Object> latencySummary() {
        Map<String, Object> summary = new TreeMap<>();
        httpLatencyMicros.forEach((route, values) -> {
            List<Long> sorted = values.stream().sorted().toList();
            summary.put(route, Map.of("count", sorted.size(), "p50", sorted.get((sorted.size() - 1) / 2),
                    "p99", sorted.get((int) Math.ceil(sorted.size() * .99) - 1), "max", sorted.getLast()));
        });
        return summary;
    }

    private CoreResponse query(SurprisingAeronClient core, CoreMessageType type, long user, byte[] payload) {
        long now = System.currentTimeMillis();
        var response = core.submit(new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), PRODUCT, CommandSource.OPERATIONS, 0, now, user, now, now), payload));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return response;
    }

    private final class Participant implements WebSocket.Listener {
        final long id; final String email; final String token;
        final Map<Long, JsonNode> orders = new ConcurrentHashMap<>();
        final Map<String, JsonNode> executions = new ConcurrentHashMap<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> subscribed = new CompletableFuture<>();
        final StringBuilder fragment = new StringBuilder();
        WebSocket ws;
        Participant(long id, String email, String token) { this.id = id; this.email = email; this.token = token; }
        void connect() {
            ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create("ws://127.0.0.1:9094/ws/v1"), this).join();
            ws.sendText(json.writeValueAsString(Map.of("op", "authenticate", "id", "auth", "token", token)), true).join();
            subscribed.orTimeout(15, TimeUnit.SECONDS).join();
        }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragment.append(data);
            if (last) {
                JsonNode event = json.readTree(fragment.toString()); fragment.setLength(0);
                String op = event.path("op").asText();
                if (op.equals("authenticated")) socket.sendText(json.writeValueAsString(Map.of("op", "subscribe", "id", "exec", "channel", "executionReports", "productLine", PRODUCT.name())), true);
                if (op.equals("subscribed")) subscribed.complete(null);
                if (op.equals("error")) { errors.add(event.toString()); subscribed.completeExceptionally(new IllegalStateException(event.toString())); }
                if (op.equals("event") && event.path("channel").asText().equals("executionReports")) {
                    assertThat(event.path("userId").asLong()).isEqualTo(id);
                    executions.put(event.path("data").path("version").asText(), event);
                }
            }
            socket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket socket, Throwable error) { errors.add(error.toString()); subscribed.completeExceptionally(error); }
    }
}
