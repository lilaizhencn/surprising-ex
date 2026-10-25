package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.repository.TriggerOrderProjectionRepository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL", matches=".+")
class TriggerOrderProjectionPostgresTest {
    private JdbcTemplate jdbc;
    private TriggerOrderProjectionRepository repository;
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"), user=System.getenv("INSTRUMENT_TEST_DB_USER"), password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        admin.execute("CREATE SCHEMA trigger_query_it");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema=trigger_query_it",user,password));
        jdbc.execute("CREATE TABLE core_trigger_order_projection (LIKE public.core_trigger_order_projection INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_trigger_projection_initialization (LIKE public.core_trigger_projection_initialization INCLUDING ALL)");
        repository=new TriggerOrderProjectionRepository(jdbc);
    }
    @AfterEach void clean() { if(jdbc!=null)jdbc.execute("DROP SCHEMA trigger_query_it CASCADE"); }
    private void ready() { jdbc.update("INSERT INTO core_trigger_projection_initialization(product_line,snapshot_log_position,initial_export_sequence) VALUES ('OPTION',32,2)"); }
    private void add(long id,long user,long time,CoreTriggerOrderStatus status) {
        var v=new CoreTriggerOrderStateView(id,ProductLine.OPTION,user,"client-"+id,"oco","74",CoreOrderSide.SELL,
                CoreTriggerOrderType.TAKE_PROFIT,CoreTriggerCondition.GREATER_OR_EQUAL,120,0,0,0,0,0,
                CoreOrderType.MARKET,CoreTimeInForce.IOC,0,1,CoreMarginMode.CROSS,CorePositionSide.NET,status,
                0,0,0,"",0,0,time,time+1,2,CoreTriggerPriceSource.LAST);
        jdbc.update("INSERT INTO core_trigger_order_projection VALUES (?,?,?,?,?,?,?,?,?,?,?)", "OPTION",id,user,v.clientTriggerOrderId(),"74",status.name(),time,time+1,2,20,CoreTriggerOrderCodec.encodeList(List.of(v)));
    }
    @Test void absentInitializationIs503RatherThanAnEmptyHistory() {
        assertThatThrownBy(()->repository.get(ProductLine.OPTION,7L,99)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        assertThatThrownBy(()->search(ProductLine.OPTION,null,null,null,null,10,null,null)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
    }
    @Test void terminalPointReadsKeepProductUserAndExactInt64Identity() {
        ready();add(Long.MAX_VALUE,7,1000,CoreTriggerOrderStatus.CANCELED);
        assertThat(repository.get(ProductLine.OPTION,7L,Long.MAX_VALUE)).hasValueSatisfying(v->{
            assertThat(v.status()).isEqualTo(CoreTriggerOrderStatus.CANCELED);assertThat(v.priceSource()).isEqualTo(CoreTriggerPriceSource.LAST);});
        assertThat(repository.get(ProductLine.OPTION,8L,Long.MAX_VALUE)).isEmpty();
        assertThatThrownBy(()->repository.get(ProductLine.SPOT,7L,Long.MAX_VALUE)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        assertThat(repository.get(ProductLine.OPTION,null,Long.MAX_VALUE)).isPresent();
    }
    @Test void bothSortDirectionsUseCreationTimeAndInt64TieBreakerWithoutGaps() {
        ready();add(5,8,1000,CoreTriggerOrderStatus.CANCELED);add(9,7,3000,CoreTriggerOrderStatus.TRIGGERED);
        add(Long.MAX_VALUE,7,2000,CoreTriggerOrderStatus.CANCELED);add(9007199254740993L,7,2000,CoreTriggerOrderStatus.CANCELED);
        for(String sort:List.of("createdAt.desc","createdAt.asc")) {
            var ids=new ArrayList<Long>();String cursor=null;
            do {var page=search(ProductLine.OPTION,null,null,null,null,1,cursor,sort);
                page.orders().forEach(v->ids.add(v.triggerOrderId()));cursor=page.nextCursor();}while(cursor!=null);
            assertThat(ids).containsExactlyElementsOf(sort.endsWith("desc")?List.of(9L,Long.MAX_VALUE,9007199254740993L,5L):List.of(5L,9007199254740993L,Long.MAX_VALUE,9L));
        }
        var filtered=search(ProductLine.OPTION,7L,"74",CoreTriggerOrderStatus.CANCELED,null,10,null,null);
        assertThat(filtered.orders()).extracting(com.surprising.trading.api.model.TriggerOrderResponse::triggerOrderId).containsExactly(Long.MAX_VALUE,9007199254740993L);
    }
    @Test void cursorCannotBeReusedForAnotherProductFilterOrSort() {
        ready();add(1,7,1000,CoreTriggerOrderStatus.CANCELED);add(2,7,2000,CoreTriggerOrderStatus.CANCELED);
        String cursor=search(ProductLine.OPTION,null,null,null,null,1,null,null).nextCursor();
        assertThatThrownBy(()->search(ProductLine.SPOT,null,null,null,null,1,cursor,null)).isInstanceOfSatisfying(ResponseStatusException.class,failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(()->search(ProductLine.OPTION,8L,null,null,null,1,cursor,null)).isInstanceOfSatisfying(ResponseStatusException.class,failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(()->search(ProductLine.OPTION,null,null,null,null,1,cursor,"createdAt.asc")).isInstanceOfSatisfying(ResponseStatusException.class,failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
    }
    @Test void corruptRowsAndUnavailableStorageAre503() {
        ready();add(1,7,1000,CoreTriggerOrderStatus.CANCELED);
        jdbc.update("UPDATE core_trigger_order_projection SET user_id=8");
        assertThatThrownBy(()->repository.get(ProductLine.OPTION,null,1)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        jdbc.execute("DROP TABLE core_trigger_order_projection");
        assertThatThrownBy(()->repository.get(ProductLine.OPTION,null,1)).isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
    }
    private com.surprising.trading.api.model.TriggerOrderQueryResponse search(ProductLine product,Long user,String instrument,
            CoreTriggerOrderStatus status,Long trigger,int limit,String cursor,String sort) {
        var properties=new com.surprising.trading.trigger.config.TriggerProperties();
        properties.setProductLine(product);
        var service=new TriggerOrderService(properties,null,null,repository);
        return new com.surprising.trading.trigger.controller.AdminTriggerOrderRequestHandler(service)
                .orders("1",null,product.name(),user,instrument,status==null?null:status.name(),trigger,limit,cursor,sort);
    }

}
