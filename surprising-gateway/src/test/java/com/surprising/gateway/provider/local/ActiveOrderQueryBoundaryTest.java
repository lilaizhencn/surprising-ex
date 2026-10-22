package com.surprising.gateway.provider.local;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.*;
import com.surprising.trading.order.controller.*;
import com.surprising.trading.order.service.*;
import com.surprising.trading.trigger.controller.*;
import com.surprising.trading.trigger.service.*;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.account.provider.config.AccountProperties;
import jakarta.validation.Validation;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Public local routes -> actual request controllers -> native services and authoritative scoped read views. */
class ActiveOrderQueryBoundaryTest {
    final ValkeyUserQueries views=mock(ValkeyUserQueries.class);
    final AeronOrderCommandService core=mock(AeronOrderCommandService.class);
    final TriggerOrderAeronGateway triggerCore=mock(TriggerOrderAeronGateway.class);
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    final jakarta.validation.ValidatorFactory validators=Validation.buildDefaultValidatorFactory();
    LocalBusinessApi api;
    ProductLine line;
    @AfterEach void close() {validators.close();}
    void setup(ProductLine product) {
        line=product;var properties=new TradingOrderProperties();properties.getKafka().setProductLine(line);
        var account=new AccountProperties();account.getKafka().setProductLine(line);
        var triggerProperties=new TriggerProperties();triggerProperties.setProductLine(line);
        var order=new OrderService(properties,null,null,core,null,null,null,null,views);
        var trigger=new TriggerOrderService(triggerProperties,triggerCore,views,null);
        var orders=new OrderRequestHandler(order,mock(AlgoOrderService.class),mock(CancelAllAfterService.class));
        var triggers=new TriggerOrderRequestHandler(trigger,order);
        var routes=new TradingLocalRoutes(mock(com.surprising.trading.maintenance.controller.AdminMaintenanceRequestHandler.class),triggers,mock(AdminTriggerOrderRequestHandler.class),mock(LeverageRequestHandler.class),mock(TradingFeeRequestHandler.class),orders,mock(AdminOrderRequestHandler.class),mock(InstrumentCoreSyncService.class),mock(com.surprising.trading.matching.service.MatchingMarketDataService.class));
        api=new LocalBusinessApi(routes,new AccountLocalRoutes(mock(com.surprising.account.provider.controller.AccountRequestHandler.class)),new InstrumentLocalRoutes(mock(com.surprising.instrument.provider.controller.InstrumentRequestHandler.class),mock(OrderBusinessSettingsRequestHandler.class)),json,validators.getValidator(),mock(com.surprising.websocket.provider.service.SubscriptionRegistry.class),account,properties);
    }
    ResponseEntity<byte[]> get(String domain,String query) {
        var headers=new HttpHeaders();headers.set("X-User-Id","42");headers.set("X-Product-Line",line.name());
        return api.invoke(domain.equals("order")?"trading-orders":"trading-trigger",URI.create("local:/api/v1/trading/"+(domain.equals("order")?"orders":"trigger-orders")+"/open?"+query),HttpMethod.GET,headers,null,Duration.ofSeconds(2));
    }
    CoreOrderStateView order(long id,String instrument) {
        return new CoreOrderStateView(id,line,42,instrument,CoreOrderSide.BUY,100,1,0,1,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"client-"+id,new UUID(0,id),1,2,1000,1000,1,"OPEN",1);
    }
    CoreTriggerOrderStateView trigger(long id,String instrument) {
        return new CoreTriggerOrderStateView(id,line,42,"client-"+id,"",instrument,CoreOrderSide.SELL,CoreTriggerOrderType.TAKE_PROFIT,CoreTriggerCondition.GREATER_OR_EQUAL,120,0,0,0,0,0,CoreOrderType.MARKET,CoreTimeInForce.IOC,0,1,CoreMarginMode.CROSS,CorePositionSide.NET,CoreTriggerOrderStatus.PENDING,0,0,0,"",0,0,1000,1000,2,0,0,CoreTriggerPriceSource.LAST);
    }
    static Stream<Arguments> products() {return Arrays.stream(ProductLine.values()).flatMap(line->(line==ProductLine.SPOT?Stream.of("order"):Stream.of("order","trigger")).map(domain->Arguments.of(line,domain)));}
    @ParameterizedTest @MethodSource("products")
    void nativePagesPreserveMaxInt64AndOwnerProductInstrumentScope(ProductLine product,String domain) {
        setup(product);var state=new UserReadView("READY","v",1,null,List.of(order(5,"1"),order(Long.MAX_VALUE,"1"),order(9007199254740993L,"1"),order(Long.MAX_VALUE-1,"2")),List.of(trigger(5,"1"),trigger(Long.MAX_VALUE,"1"),trigger(9007199254740993L,"1"),trigger(Long.MAX_VALUE-1,"2")),1,List.of());
        when(views.require(eq(line),eq(42L),any())).thenReturn(state);
        List<Long> ids=new ArrayList<>();String cursor=null;int pages=0;
        do {var result=get(domain,"instrumentId=1&limit=1"+(domain.equals("order")?"&minExportSequence=9":"")+(cursor==null?"":"&cursor="+cursor));assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body=json.readTree(result.getBody());body.path("orders").forEach(row->ids.add(Long.parseLong(row.path(domain.equals("order")?"orderId":"triggerOrderId").asText())));cursor=body.path("nextCursor").isNull()?null:body.path("nextCursor").asText(null);assertThat(++pages).isLessThan(5);
        }while(cursor!=null);
        assertThat(ids).containsExactly(Long.MAX_VALUE,9007199254740993L,5L);
        verify(views,atLeastOnce()).require(line,42L,domain.equals("order")?9L:null);verifyNoInteractions(core,triggerCore);
        for(ProductLine other:ProductLine.values())if(other!=line)verify(views,never()).require(eq(other),anyLong(),any());
    }
    static String encoded(String value) {return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static Stream<Arguments> badQueries() {
        return Stream.of("order","trigger").flatMap(domain->{String prefix=domain.equals("order")?"core-open:v1:":"trigger:";return Stream.of("cursor=!!","cursor="+encoded("wrong:1"),"cursor="+encoded(prefix+"0"),"cursor="+encoded(prefix+"-1"),"cursor="+encoded(prefix+"9223372036854775808"),"limit=0","limit=1001","instrumentId=BTCUSDT","userId=43").map(query->Arguments.of(domain,query));});
    }
    @ParameterizedTest @MethodSource("badQueries")
    void malformedOrForgedQueryNeverReadsStateOrCallsCore(String domain,String query) {
        setup(ProductLine.LINEAR_PERPETUAL);var result=get(domain,query);assertThat(result.getStatusCode()).isEqualTo(query.equals("userId=43")?HttpStatus.FORBIDDEN:HttpStatus.BAD_REQUEST);verifyNoInteractions(views,core,triggerCore);
    }
}
