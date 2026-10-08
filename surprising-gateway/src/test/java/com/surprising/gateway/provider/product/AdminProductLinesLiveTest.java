package com.surprising.gateway.provider.product;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 独立环境：开始时接入表全关闭，五 Core 已运行；OPTION 故意未启动，第二阶段再启动。 */
@EnabledIfEnvironmentVariable(named="PRODUCT_ACCESS_IT_BASE_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class AdminProductLinesLiveTest {
    private static final String ADMIN="access-admin@local-qa.example", USER="access-user@local-qa.example";
    private static final String PASSWORD="Local-Access-QA-2026!";
    private final String base=System.getenv("PRODUCT_ACCESS_IT_BASE_URL");
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void emptyGatewayAcceptsAdminConfigurationAndHotConnectsFiveProductsWithoutRestart() throws Exception {
        assertThat(ok(send("GET","/api/v1/runtime",null,null)).path("productLines").size()).isZero();
        ok(send("GET","/api/v1/gateway/instrument/asset-scales",null,null));
        ok(send("GET","/api/v1/gateway/instrument/list",null,null));
        var actor=ok(send("POST","/api/v1/auth/register",null,Map.of("email",USER,"password",PASSWORD)));
        var admin=ok(send("POST","/api/v1/auth/register",null,Map.of("email",ADMIN,"password",PASSWORD)));
        assertThat(send("GET","/api/v1/admin/product-lines",null,null).statusCode()).isEqualTo(401);
        assertThat(send("GET","/api/v1/admin/product-lines",actor.path("accessToken").asString(),null).statusCode()).isEqualTo(403);
        String db=Objects.requireNonNull(System.getenv("PRODUCT_ACCESS_IT_DB_URL"));
        assertThat(db).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sixline_qa");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(db,"sixline_qa","qa"));
        jdbc.update("INSERT INTO gateway_user_roles(user_id,role_id) SELECT ?,role_id FROM gateway_roles WHERE role_code='ADMIN'",admin.path("user").path("userId").asLong());
        String token=login(ADMIN);
        assertThat(ok(send("GET","/api/v1/admin/product-lines",token,null)).size()).isEqualTo(6);
        for(Object invalid:List.of(Map.of("expectedVersion","1","reason","bad type"),
                Map.of("expectedVersion",1.5,"reason","fraction"),Map.of("expectedVersion",1,"reason"," "),
                Map.of("expectedVersion",1,"reason","unknown","enabled",false)))
            assertThat(send("POST","/api/v1/admin/product-lines/SPOT/enable",token,invalid).statusCode()).isEqualTo(400);
        assertThat(send("POST","/api/v1/admin/product-lines/UNKNOWN/enable",token,Map.of("expectedVersion",1,"reason","unknown")).statusCode()).isEqualTo(400);
        var normal=Map.of("expectedVersion",1,"reason","本地验证后台热接入");
        try(var socket=new Socket()) {
            // WebSocket 先建立；后台启用之后使用同一条连接订阅，不重建连接。
            socket.connect(actor.path("accessToken").asString());
            for(var product:ProductLine.values()) {
                if(product==ProductLine.OPTION)continue;
                assertThat(send("POST","/api/v1/admin/product-lines/"+product+"/enable",actor.path("accessToken").asString(),normal).statusCode()).isEqualTo(403);
                ok(send("POST","/api/v1/admin/product-lines/"+product+"/enable",token,normal));
                awaitConnected(product);
                socket.subscribe(product);
                fund(actor.path("user").path("userId").asLong(), product);
                ok(send("GET","/api/v1/gateway/account/balance?asset=USDT&productLine="+product,actor.path("accessToken").asString(),null));
            }
        }
        assertThat(send("POST","/api/v1/admin/product-lines/SPOT/enable",token,normal).statusCode()).isEqualTo(409);
        assertBalance(actor.path("accessToken").asString());
        ok(send("POST","/api/v1/admin/product-lines/OPTION/enable",token,normal));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(true) {
            var statuses=ok(send("GET","/api/v1/admin/product-lines",token,null));
            boolean failed=false;
            for(var item:statuses) if("OPTION".equals(item.path("configuration").path("productLine").asString()))
                failed="FAILED".equals(item.path("runtimeStatus").asString());
            if(failed)break;
            assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(200);
        }
        assertThat(ok(send("GET","/api/v1/runtime",null,null)).path("productLines").size()).isEqualTo(5);
        assertThat(send("GET","/actuator/health/readiness",null,null).statusCode()).isEqualTo(503);
        ok(send("GET","/actuator/health/liveness",null,null));
        assertBalance(actor.path("accessToken").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_admin_operation_logs WHERE service='product-lines' AND success",Integer.class)).isEqualTo(6);
        System.out.println("PRODUCT_ACCESS_PHASE1=PASS emptyBoot=true hotProducts=5 oneSocket=true failedCoreIsolated=true audit=6");
    }

    @Test void optionRetriesAndSavedProductsSurviveGatewayRestart() throws Exception {
        for(var product:ProductLine.values())awaitConnected(product);
        String token=login(USER);
        assertBalance(token);
        try(var socket=new Socket()) {
            socket.connect(token);
            for(var product:ProductLine.values())socket.subscribe(product);
        }
        System.out.println("PRODUCT_ACCESS_PHASE2=PASS connectedProducts=6 balance=123456 oneSocket=true");
    }

    private void fund(long userId, ProductLine product) throws Exception {
        var seed=Map.of("userId",userId,"asset","USDT","amountUnits",product==ProductLine.SPOT?123456:10,
                "accountType",product.accountTypeCode(),"referenceId","access-hot-seed-"+product,"reason","hot access QA");
        var request=HttpRequest.newBuilder(URI.create(base+"/internal/v1/operations/liquidity/balance-adjustments"))
                .header("Content-Type","application/json").header("X-Operations-Token",System.getenv("PRODUCT_ACCESS_IT_OPERATIONS_TOKEN"))
                .header("X-Operation-Id","access-hot-seed-"+product).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(seed))).build();
        ok(http.send(request,HttpResponse.BodyHandlers.ofString()));
    }
    private void awaitConnected(ProductLine product)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(true) {
            for(var item:ok(send("GET","/api/v1/runtime",null,null)).path("productLines"))
                if(product.name().equals(item.asString()))return;
            assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(200);
        }
    }
    private String login(String email)throws Exception {
        return ok(send("POST","/api/v1/auth/login",null,Map.of("username",email,"password",PASSWORD))).path("accessToken").asString();
    }
    private void assertBalance(String token)throws Exception {
        assertThat(ok(send("GET","/api/v1/gateway/account/balance?asset=USDT&productLine=SPOT",token,null)).path("equityUnits").asLong()).isEqualTo(123456);
    }
    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200,299);
        return json.readTree(response.body());
    }
    private HttpResponse<String> send(String method,String path,String token,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private class Socket implements WebSocket.Listener,AutoCloseable {
        private WebSocket socket;
        private final BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>();
        private final StringBuilder fragments=new StringBuilder();
        void connect(String token) {
            socket=http.newWebSocketBuilder().header("Authorization","Bearer "+token)
                    .buildAsync(URI.create(base.replace("http://","ws://")+"/ws/v1"),this).join();
        }
        void subscribe(ProductLine product)throws Exception {
            socket.sendText(json.writeValueAsString(Map.of("op","subscribe","id",product.name(),"productLine",product.name(),"channel","mark","instrumentId","604")),true).join();
            var message=messages.poll(10,TimeUnit.SECONDS);
            assertThat(message).isNotNull();assertThat(message.path("op").asString()).isEqualTo("subscribed");
        }
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last) {
            fragments.append(text);if(last){messages.add(json.readTree(fragments.toString()));fragments.setLength(0);}
            socket.request(1);return CompletableFuture.completedFuture(null);
        }
        @Override public void close(){if(socket!=null)socket.sendClose(WebSocket.NORMAL_CLOSURE,"QA complete").join();}
    }
}
