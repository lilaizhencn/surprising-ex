package com.surprising.gateway.provider.product;

import static org.assertj.core.api.Assertions.*;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 一台隔离 Gateway + 六条真实 Core。只允许显式指定本机测试环境，禁止指向线上。 */
@EnabledIfEnvironmentVariable(named = "MULTI_PRODUCT_GATEWAY_IT_BASE_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class MultiProductGatewayHttpIntegrationTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base = System.getenv("MULTI_PRODUCT_GATEWAY_IT_BASE_URL");
    private final String operations = Objects.requireNonNull(System.getenv("MULTI_PRODUCT_GATEWAY_IT_OPERATIONS_TOKEN"));
    private record User(long id, String token) { }
    private record Market(ProductLine line, String id, long change, long tickUnits, long price) { }

    private static com.sun.net.httpserver.HttpServer quotes;
    @BeforeAll static void startIdentityQuoteFixture() throws Exception {
        // 不启动外部行情服务；划转安全校验仍真实调用 FX，只提供 USDT -> USDT 恒等报价。
        quotes = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",
                Integer.parseInt(Objects.requireNonNull(System.getenv("MULTI_PRODUCT_GATEWAY_IT_FX_PORT")))), 0);
        quotes.createContext("/api/v1/price/fx/convert", exchange -> {
            var query = new HashMap<String,String>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = part.split("=",2);
                query.put(pair[0],java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8));
            }
            boolean valid = "USDT".equals(query.get("fromCurrency")) && "USDT".equals(query.get("toCurrency"));
            byte[] body = ("{\"convertedAmount\":\"" + new java.math.BigDecimal(query.get("amount")).toPlainString()
                    + "\",\"rateTime\":\"" + Instant.now() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(valid ? 200 : 400,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        quotes.start();
    }
    @AfterAll static void stopQuoteFixture() { if(quotes!=null)quotes.stop(0); }
    @org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable(named = "MULTI_PRODUCT_GATEWAY_IT_REALTIME", matches = "true")
    @Test void sixProductsTradeTransferAndPushThroughOneGateway() throws Exception {
        exerciseSixProducts(false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MULTI_PRODUCT_GATEWAY_IT_ROUTED", matches = "true")
    void sixRealCoresRouteTheirExecutionsThroughOneGatewaySocket() throws Exception {
        exerciseSixProducts(true);
    }

    private void exerciseSixProducts(boolean coreRealtime) throws Exception {
        assertThat(get("/api/v1/runtime", null).path("productLines").size()).isEqualTo(6);
        assertThat(get("/actuator/health/readiness", null).path("status").asString()).isEqualTo("UP");
        User user = register("trader"), maker = register("maker");
        for (var line : ProductLine.values()) for (var actor : List.of(user,maker)) {
            fund(actor,line,"USDT",1_000_000_000_000L);
            fund(actor,line,"BTC",100_000_000_000L);
        }
        assertThat(send("GET","/api/v1/gateway/account/balance?asset=USDT",user.token(),null,false).statusCode()).isEqualTo(400);
        assertThat(send("GET","/api/v1/gateway/account/balance?asset=USDT&productLine=SPOT&accountType=USDT_PERPETUAL",user.token(),null,false).statusCode()).isEqualTo(400);
        assertThat(send("GET","/api/v1/gateway/account/balance?asset=USDT&productLine=SPOT&userId="+maker.id(),user.token(),null,false).statusCode()).isEqualTo(403);
        // 保留独立做市服务的内部 HTTP 契约，生产边缘禁止公开该路径。
        assertThat(get("/api/v1/accounts/balance?asset=USDT&productLine=SPOT&userId="+user.id(),null)
                .path("equityUnits").asLong()).isEqualTo(1_000_000_000_000L);
        for (var line : ProductLine.values()) {
            var adjustment = Map.of("userId",user.id(),"accountType",line.accountTypeCode(),"asset","USDT",
                    "amountUnits",7,"referenceId","internal-"+UUID.randomUUID(),"reason","internal HTTP QA");
            for(int retry=0;retry<2;retry++)
                ok(send("POST","/api/v1/accounts/admin/product-balance-adjustments",null,adjustment,false));
            assertThat(get("/api/v1/gateway/account/balance?asset=USDT&productLine="+line,user.token())
                    .path("equityUnits").asLong()).isEqualTo(1_000_000_000_007L);
        }
        long original = total(user);
        for (var line : ProductLine.values()) {
            if (line == ProductLine.SPOT) continue;
            transfer(user, ProductLine.SPOT, line);
            transfer(user, line, ProductLine.SPOT);
            assertThat(total(user)).isEqualTo(original);
        }
        var template = (ObjectNode) get("/api/v1/gateway/instrument/latest?instrumentId=604&productLine=LINEAR_PERPETUAL",null);
        var markets = new ArrayList<Market>();
        for (var line : ProductLine.values()) markets.add(createMarket(template,line));
        Properties settings = new Properties();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,Objects.requireNonNull(System.getenv("MULTI_PRODUCT_GATEWAY_IT_KAFKA")));
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,"org.apache.kafka.common.serialization.StringSerializer");
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,"org.apache.kafka.common.serialization.StringSerializer");
        settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,"5000");
        settings.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,"5000");
        settings.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,"10000");
        var cores = new EnumMap<ProductLine,AeronClientPool>(ProductLine.class);
        var feedFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var socket = new FeedSocket();
        var makerSocket = new FeedSocket();
        String publicChannel = coreRealtime ? "trades" : "mark";
        try (var prices = new KafkaProducer<String,String>(settings); var feed = Executors.newSingleThreadScheduledExecutor()) {
            for (var market : markets) cores.put(market.line(),new AeronClientPool("multi-product-it",market.line(),List.of("127.0.0.1"),"127.0.0.1",Duration.ofSeconds(5),1));
            socket.connect(user);
            if (coreRealtime) makerSocket.connect(maker);
            for (var market : markets) {
                socket.subscribe(publicChannel,market);
                socket.subscribe("executionReports",market);
                if (coreRealtime) makerSocket.subscribe("executionReports",market);
            }
            socket.awaitSubscriptions(12);
            if (coreRealtime) {
                makerSocket.awaitSubscriptions(6);
                socket.awaitReadySnapshots();
                makerSocket.awaitReadySnapshots();
            }
            feed.scheduleAtFixedRate(() -> {
                try { for (var market : markets) prices.send(new ProducerRecord<>(ProductTopicNames.of(market.line()).priceEventsTopic(),market.id(),priceEvent(market))).get(5,TimeUnit.SECONDS); }
                catch (Throwable failure) { feedFailure.compareAndSet(null,failure); }
            },0,300,TimeUnit.MILLISECONDS);
            for (var market : markets) {
                var core = cores.get(market.line());
                awaitCoreInstrument(core, market);
                var mark = core.command(CoreMessageType.APPLY_MARK_PRICE,UUID.randomUUID(),0,
                        TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(market.id(),market.price(),500_000,market.line()==ProductLine.OPTION?500_000:0,System.currentTimeMillis(),System.currentTimeMillis())));
                assertThat(mark.commandStatus()).as(mark.toString()).isEqualTo(ResponseStatus.APPLIED);
                awaitOrderReadiness(user,market);
                var before = combined(core,user,maker);
                // 模拟做市账户提供开仓与平仓流动性，全部请求走同一 HTTP 端口。
                command(maker,market,"",order(maker,market,"SELL",market.price(),false));
                command(user,market,"",order(user,market,"BUY",market.price(),false));
                if (market.line()!=ProductLine.SPOT)
                    assertThat(state(core,user).positions()).anyMatch(p -> p.instrumentId().equals(market.id()) && p.signedQuantitySteps()==1);
                var resting = command(user,market,"",order(user,market,"BUY",market.price()-100,false));
                long restingId = resting.path("prospectiveOrderIds").get(0).asLong();
                var canceled = command(user,market,"/cancel",Map.of("userId",user.id(),"instrumentId",market.id(),"orderId",restingId));
                assertThat(canceled.path("result").path("status").asString()).isEqualTo("CANCELED");
                if (!coreRealtime) {
                    // Kafka 模式从真实撤单回执构造输入；完整 Aeron 模式只消费 Core 产生的事件。
                    var orderEvent = new com.surprising.trading.api.model.OrderEvent(System.currentTimeMillis(),restingId,
                            user.id(),market.id(),com.surprising.trading.api.model.OrderEventType.CANCELED,
                            com.surprising.trading.api.model.OrderStatus.CANCELED,"multi QA",Instant.now());
                    prices.send(new ProducerRecord<>(ProductTopicNames.of(market.line()).orderEventsTopic(),market.id(),
                            json.writeValueAsString(orderEvent))).get(5,TimeUnit.SECONDS);
                }
                command(maker,market,"",order(maker,market,"BUY",market.price(),market.line()!=ProductLine.SPOT));
                command(user,market,"",order(user,market,"SELL",market.price(),market.line()!=ProductLine.SPOT));
                for (var actor : List.of(user,maker)) {
                    var current=state(core,actor);
                    assertThat(current.reservations()).isEmpty();
                    assertThat(current.positions()).allMatch(p -> p.signedQuantitySteps()==0 && p.positionMarginUnits()==0);
                    assertThat(current.balances()).allMatch(b -> b.lockedUnits()==0);
                }
                assertThat(combined(core,user,maker)).as("funds conservation "+market.line()).isEqualTo(before);
                System.out.println("MULTI_GATEWAY_TRADE_PASS productLine="+market.line()+" fundsDifference=0");
            }
            socket.awaitProducts(publicChannel,EnumSet.allOf(ProductLine.class));
            socket.awaitProducts("executionReports",EnumSet.allOf(ProductLine.class));
            if (coreRealtime) {
                makerSocket.awaitProducts("executionReports", EnumSet.allOf(ProductLine.class));
                assertThat(socket.events).filteredOn(event -> "executionReports".equals(event.path("channel").asString()))
                        .allMatch(event -> event.path("userId").asLong() == user.id());
                assertThat(makerSocket.events).allMatch(event -> event.path("userId").asLong() == maker.id());
                assertThat(makerSocket.errors).isEmpty();
            }
            assertThat(feedFailure.get()).isNull();
            assertThat(socket.errors).isEmpty();
            System.out.println("MULTI_GATEWAY_WEBSOCKET_PASS products=6 sockets=1 source="
                    + (coreRealtime ? "CORE_ROUTER" : "KAFKA_FIXTURE"));
            feed.shutdownNow();
        } finally {
            socket.close();
            makerSocket.close();
            cores.values().forEach(AeronClientPool::close);
        }
    }

    private void awaitCoreInstrument(AeronClientPool core, Market market) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            var response = core.query(CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, UUID.randomUUID(), 0,
                    CoreMaintenanceCodec.encodeQuery(new CoreMaintenanceCodec.Query(market.id(), 0, 1)));
            if (response.status() == ResponseStatus.OK) return;
            assertThat(response.resultCode()).as("instrument registration " + market.line())
                    .isEqualTo(CoreResultCode.ENTITY_NOT_FOUND);
            if (System.nanoTime() > deadline)
                throw new AssertionError("Core instrument registration timed out: " + market);
            Thread.sleep(100);
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "MULTI_PRODUCT_GATEWAY_IT_REALTIME", matches = "true")
    void aeronRealtimeUsesOneSocketForSixProductsAndKeepsPrivateUsersIsolated() throws Exception {
        var user = register("realtime");
        var other = register("other-user");
        try (var socket = new FeedSocket(); var otherSocket = new FeedSocket();
             var aeron = io.aeron.Aeron.connect(new io.aeron.Aeron.Context().aeronDirectoryName(
                     Objects.requireNonNull(System.getenv("MULTI_PRODUCT_GATEWAY_IT_AERON_DIR"))));
             var publication = aeron.addPublication("aeron:udp?endpoint=127.0.0.1:19430",2103)) {
            socket.connect(user);otherSocket.connect(other);
            for (var line : ProductLine.values()) {
                var market = new Market(line,"604",1,1,1);
                socket.subscribe("mark",market);
                socket.subscribe("executionReports",market);
                otherSocket.subscribe("executionReports",market);
            }
            socket.awaitSubscriptions(12);otherSocket.awaitSubscriptions(6);
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!publication.isConnected()) {
                if(System.nanoTime()>deadline)throw new AssertionError("Aeron realtime publication unavailable");
                Thread.sleep(10);
            }
            for (var line : ProductLine.values()) {
                var payload = java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putLong(123).putLong(line.ordinal()+1).putLong(1).put((byte)0).put((byte)1).array();
                for (var frame : List.of(
                        new RealtimeFrame(line,RealtimeFrame.Kind.MARK,0,100,0,System.currentTimeMillis(),0,"604","mark",
                                json.writeValueAsBytes(Map.of("sentinel",line.name()))),
                        new RealtimeFrame(line,RealtimeFrame.Kind.EXECUTION,user.id(),101,0,System.currentTimeMillis(),0,"604","execution",payload))) {
                    byte[] bytes=RealtimeFrameCodec.encode(frame);
                    var buffer=new org.agrona.concurrent.UnsafeBuffer(bytes);
                    while(publication.offer(buffer)<0) {
                        if(System.nanoTime()>deadline)throw new AssertionError("Aeron realtime publication backpressured");
                        Thread.sleep(1);
                    }
                }
            }
            socket.awaitProducts("mark",EnumSet.allOf(ProductLine.class));
            socket.awaitProducts("executionReports",EnumSet.allOf(ProductLine.class));
            for(var event:socket.events) {
                var line=ProductLine.valueOf(event.path("productLine").asString());
                if("mark".equals(event.path("channel").asString()))
                    assertThat(event.path("data").path("value").path("sentinel").asString()).isEqualTo(line.name());
                if("executionReports".equals(event.path("channel").asString())) {
                    assertThat(event.path("userId").asLong()).isEqualTo(user.id());
                    assertThat(event.path("data").path("value").path("priceTicks").asLong()).isEqualTo(line.ordinal()+1);
                }
            }
            assertThat(socket.errors).isEmpty();assertThat(otherSocket.errors).isEmpty();
            assertThat(otherSocket.events).noneMatch(event -> "executionReports".equals(event.path("channel").asString()));
            System.out.println("MULTI_GATEWAY_AERON_WEBSOCKET_PASS products=6 socketsPerUser=1 privateIsolation=PASS");
        }
    }
    private Market createMarket(ObjectNode template,ProductLine line) throws Exception {
        var draft=template.deepCopy();
        draft.remove(List.of("changeId","lastChangeId","createdAt","updatedAt","baseAsset","quoteAsset","settleAsset","contractValueAsset","version"));
        draft.putNull("instrumentId");draft.put("symbol","QA-"+line+"-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT));
        draft.put("status","TRADING");draft.put("contractType",line.contractTypeCode());
        draft.put("instrumentType",switch(line){case SPOT->"SPOT";case LINEAR_PERPETUAL,INVERSE_PERPETUAL->"PERPETUAL";case LINEAR_DELIVERY,INVERSE_DELIVERY->"DELIVERY";case OPTION->"OPTION";});
        draft.put("minNotionalUnits",1);draft.put("priceTickUnits",10_000_000L);draft.put("quantityStepUnits",100_000L);
        draft.put("notionalMultiplierUnits",line==ProductLine.INVERSE_PERPETUAL||line==ProductLine.INVERSE_DELIVERY?100_000_000_000L:10_000L);
        if (!line.isFundingProduct()) for(var field:List.of("fundingIntervalHours","interestRatePpm","fundingRateCapPpm","fundingRateFloorPpm")) draft.put(field,0);
        if(line==ProductLine.SPOT){draft.put("reduceOnlyEnabled",false);draft.putArray("riskLimitBrackets");draft.putArray("indexSources");}
        if(line==ProductLine.INVERSE_PERPETUAL||line==ProductLine.INVERSE_DELIVERY)draft.put("settleAssetId",template.path("baseAssetId").asInt());
        if(line==ProductLine.LINEAR_DELIVERY||line==ProductLine.INVERSE_DELIVERY||line==ProductLine.OPTION){draft.put("expiryTime",Instant.now().plusSeconds(86400).toString());draft.put("deliveryTime",Instant.now().plusSeconds(86700).toString());draft.put("settlementMethod","CASH");}
        if(line==ProductLine.OPTION){
            draft.put("underlyingInstrumentId","604");draft.put("underlyingProductLine","LINEAR_PERPETUAL");draft.put("strikePriceUnits",5_000_000_000_000L);draft.put("optionType","CALL");draft.put("optionExerciseStyle","EUROPEAN");
            for(var source:draft.path("indexSources")){((ObjectNode)source).put("parser","OPTION_RISK_TICKER").put("websocketParser","OPTION_RISK_TICKER").put("quoteCurrency","USDT").put("targetQuoteCurrency","USDT");}
        }
        var created=ok(send("POST","/internal/v1/operations/liquidity/instruments?reason=multi-gateway-qa",null,draft,true));
        System.out.println("MULTI_GATEWAY_MARKET_CREATED productLine="+line+" instrumentId="+created.path("instrumentId"));
        return new Market(line,created.path("instrumentId").asString(),created.path("lastChangeId").asLong(),10_000_000L,line==ProductLine.OPTION?10_000:500_000);
    }
    private void awaitOrderReadiness(User actor,Market market) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(true){var response=send("POST","/api/v1/gateway/trading/test?productLine="+market.line(),actor.token(),order(actor,market,"BUY",market.price(),false),false);
            if(response.statusCode()==200 && json.readTree(response.body()).path("accepted").asBoolean())return;
            if(System.nanoTime()>deadline)throw new AssertionError("readiness "+market.line()+": "+response.body());Thread.sleep(100);}
    }
    private Map<String,Object> order(User actor,Market market,String side,long price,boolean reduce){return Map.of("userId",actor.id(),"clientOrderId","multi-"+UUID.randomUUID(),"instrumentId",market.id(),"side",side,"orderType","LIMIT","timeInForce","GTC","priceTicks",price,"quantitySteps",1,"reduceOnly",reduce,"postOnly",false);}
    private JsonNode command(User actor,Market market,String suffix,Object body) throws Exception {
        var receipt=ok(send("POST","/api/v1/gateway/trading"+suffix+"?productLine="+market.line(),actor.token(),body,false));
        String url=receipt.path("commandResultUrl").asString();assertThat(url).endsWith("?productLine="+market.line());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(receipt.path("result").isMissingNode()||receipt.path("result").isNull()){if(System.nanoTime()>deadline)throw new AssertionError("command unknown: "+receipt);Thread.sleep(20);receipt=get(url,actor.token());}
        assertThat(receipt.path("code").asString()).as(receipt.toString()).isEqualTo("NONE");return receipt;
    }
    private CoreUserStateView state(AeronClientPool core,User user){return CoreStateQueryCodec.decodeUserState(core.query(CoreMessageType.USER_STATE_QUERY,UUID.randomUUID(),user.id(),new byte[0]).data());}
    private Map<String,Long> combined(AeronClientPool core,User user,User maker){
        var totals=new TreeMap<String,Long>();
        for(var actor:List.of(user,maker))for(var balance:state(core,actor).balances())totals.merge(balance.asset(),Math.addExact(balance.availableUnits(),balance.lockedUnits()),Math::addExact);
        for(var balance:CoreStateQueryCodec.decodeTreasuryState(core.query(CoreMessageType.TREASURY_STATE_QUERY,UUID.randomUUID(),0,new byte[0]).data()))
            totals.merge(balance.asset(),java.util.stream.LongStream.of(balance.feeBalanceUnits(),balance.insuranceBalanceUnits(),balance.liquidationFeeBalanceUnits(),balance.fundingResidualBalanceUnits(),balance.roundingResidualBalanceUnits(),balance.clearingPnlBalanceUnits(),-balance.insuranceDeficitUnits()).reduce(0,Math::addExact),Math::addExact);
        totals.entrySet().removeIf(e->e.getValue()==0);return totals;
    }
    private void transfer(User user,ProductLine source,ProductLine target) throws Exception {
        String ref="transfer-"+UUID.randomUUID();var body=Map.of("sourceAccountType",source.accountTypeCode(),"targetAccountType",target.accountTypeCode(),"asset","USDT","amountUnits",10000,"referenceId",ref,"reason","multi QA");
        for(int retry=0;retry<2;retry++)assertThat(ok(send("POST","/api/v1/gateway/account/transfers",user.token(),body,false)).path("status").asString()).isEqualTo("COMPLETED");
    }
    private long total(User user) throws Exception {long total=0;for(var line:ProductLine.values())total=Math.addExact(total,get("/api/v1/gateway/account/balance?asset=USDT&productLine="+line,user.token()).path("equityUnits").asLong());return total;}
    private void fund(User user,ProductLine line,String asset,long units) throws Exception {String ref="seed-"+UUID.randomUUID();var body=Map.of("userId",user.id(),"accountType",line.accountTypeCode(),"asset",asset,"amountUnits",units,"referenceId",ref,"reason","multi QA");for(int retry=0;retry<2;retry++)ok(send("POST","/internal/v1/operations/liquidity/balance-adjustments",null,body,true));}
    private User register(String name) throws Exception {var result=ok(send("POST","/api/v1/auth/register",null,Map.of("email",name+"-"+UUID.randomUUID()+"@multi-qa.example","password","Multi-QA-password-42!"),false));return new User(result.path("user").path("userId").asLong(),result.path("accessToken").asString());}
    private JsonNode get(String path,String token) throws Exception{return ok(send("GET",path,token,null,false));}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isBetween(200,299);return json.readTree(response.body());}
    private HttpResponse<String> send(String method,String path,String token,Object body,boolean ops) throws Exception {var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));if(token!=null)builder.header("Authorization","Bearer "+token);if(ops)builder.header("X-Operations-Token",operations).header("X-Operation-Id","qa-"+UUID.randomUUID());builder.header("Content-Type","application/json");builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
    private String priceEvent(Market market){
        var root=json.createObjectNode();String now=Instant.now().toString();root.put("schemaVersion",1).put("eventType","MARK_PRICE").put("instrumentId",market.id()).put("generatedAt",now);
        var mark=root.putObject("markPrice");mark.put("calculatedAt",now).put("basisWindowSeconds",1);
        mark.putObject("result").put("productLine",market.line().name()).put("instrumentId",market.id()).put("instrumentChangeId",market.change()).put("markPriceUnits",market.price()*market.tickUnits()).put("markPriceTicks",market.price()).put("sequence",System.currentTimeMillis()).put("timeUntilFundingSeconds",1).put("basisWindowSeconds",1).put("status","HEALTHY").put("eventTime",now).put("publishedAt",now).put("markPrice",market.price()/10.0).put("indexPrice",50000).put("sameExpiryForwardPrice",50000);
        var index=mark.putObject("indexInput");index.put("instrumentId",market.id()).put("indexPrice",50000).put("eventTime",now).put("status","HEALTHY").put("sequence",System.currentTimeMillis()).put("componentCount",1).put("validComponentCount",1);index.putArray("components");return json.writeValueAsString(root);
    }
    private final class FeedSocket implements WebSocket.Listener,AutoCloseable {
        WebSocket socket;final BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>();final List<String> errors=new CopyOnWriteArrayList<>();final StringBuilder fragments=new StringBuilder();
        void connect(User user)throws Exception{socket=http.newWebSocketBuilder().buildAsync(URI.create(base.replace("http://","ws://")+"/ws/v1"),this).join();socket.sendText(json.writeValueAsString(Map.of("op","authenticate","id","auth","token",user.token())),true).join();await("authenticated",1);}
        void subscribe(String channel,Market market){socket.sendText(json.writeValueAsString(Map.of("op","subscribe","id",channel+market.line(),"channel",channel,"instrumentId",market.id(),"productLine",market.line().name())),true).join();}
        void awaitSubscriptions(int count)throws Exception{await("subscribed",count);}
        void awaitReadySnapshots() throws Exception {
            var remaining = EnumSet.allOf(ProductLine.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!remaining.isEmpty()) {
                for (var snapshot : snapshots)
                    if ("READY".equals(snapshot.path("data").path("status").asString()))
                        remaining.remove(ProductLine.valueOf(snapshot.path("productLine").asString()));
                if (System.nanoTime() > deadline)
                    throw new AssertionError("Core realtime snapshots missing=" + remaining + " errors=" + errors);
                Thread.sleep(50);
            }
        }
        void await(String op,int count)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(count>0){JsonNode node=messages.poll(100,TimeUnit.MILLISECONDS);if(node!=null&&op.equals(node.path("op").asString()))count--;if(System.nanoTime()>deadline)throw new AssertionError("WebSocket "+op+" missing="+count+" errors="+errors);}}
        void awaitProducts(String channel,Set<ProductLine> expected)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!expected.isEmpty()){for(var event:events)if(channel.equals(event.path("channel").asString()))expected.remove(ProductLine.valueOf(event.path("productLine").asString()));if(System.nanoTime()>deadline)throw new AssertionError("WebSocket missing "+channel+" "+expected+" errors="+errors);Thread.sleep(50);}}
        final List<JsonNode> events=new CopyOnWriteArrayList<>();
        final List<JsonNode> snapshots=new CopyOnWriteArrayList<>();
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){fragments.append(data);if(last){var event=json.readTree(fragments.toString());fragments.setLength(0);if("error".equals(event.path("op").asString()))errors.add(event.toString());if("event".equals(event.path("op").asString()))events.add(event);if("snapshot".equals(event.path("op").asString()))snapshots.add(event);messages.add(event);}socket.request(1);return CompletableFuture.completedFuture(null);}
        @Override public void onError(WebSocket socket,Throwable failure){errors.add(failure.toString());}
        @Override public void close(){if(socket!=null)socket.sendClose(WebSocket.NORMAL_CLOSURE,"QA complete").join();}
    }
}
