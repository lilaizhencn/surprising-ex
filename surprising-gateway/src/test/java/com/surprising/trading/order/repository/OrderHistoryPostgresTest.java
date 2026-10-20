package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderStatus;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL", matches=".+")
class OrderHistoryPostgresTest {
    private JdbcTemplate jdbc;
    private AeronOrderProjectionRepository repository;
    @BeforeEach void prepare() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"), user=System.getenv("INSTRUMENT_TEST_DB_USER"), password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        new JdbcTemplate(new DriverManagerDataSource(url,user,password)).execute("CREATE SCHEMA order_history_it");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema=order_history_it",user,password));
        jdbc.execute("CREATE TABLE core_order_projection (LIKE public.core_order_projection INCLUDING ALL)");
        repository=new AeronOrderProjectionRepository(jdbc);
    }
    @AfterEach void clean() { if(jdbc!=null) jdbc.execute("DROP SCHEMA order_history_it CASCADE"); }
    @Test void terminalStatusFiltersBeforeLimitAndTraversesEqualUpdateTimesExactly() {
        add(ProductLine.SPOT, 91, 7, "868", "CANCELED", 1000, 4000);
        add(ProductLine.SPOT, 92, 7, "868", "FILLED", 1000, 3000);
        add(ProductLine.SPOT, 9007199254740993L, 7, "868", "FILLED", 1000, 3000);
        add(ProductLine.SPOT, Long.MAX_VALUE, 7, "868", "OPEN", 1000, 5000);
        var first=repository.historyOrders(ProductLine.SPOT,7,"868",1,null,null,null,null,null,OrderStatus.FILLED);
        assertThat(first.orders()).extracting(v->v.orderId()).containsExactly(9007199254740993L);
        assertThat(first.hasMore()).isTrue();
        var second=repository.historyOrders(ProductLine.SPOT,7,"868",1,null,null,null,first.nextCursor(),null,OrderStatus.FILLED);
        assertThat(second.orders()).extracting(v->v.orderId()).containsExactly(92L);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(repository.historyOrders(ProductLine.SPOT,7,"868",10,null,null,null,null,null,null).orders())
                .extracting(v->v.orderId()).containsExactly(91L,9007199254740993L,92L);
    }
    @Test void productAccountInstrumentAndInclusiveCreationTimesRemainIsolated() {
        for(var line:ProductLine.values()) add(line,91,7,"868","FILLED",1000,2000);
        add(ProductLine.SPOT,92,8,"868","FILLED",1000,3000);
        add(ProductLine.SPOT,93,7,"869","FILLED",1000,4000);
        for(var line:ProductLine.values()) assertThat(repository.historyOrders(line,7,"868",10,null,1000L,1000L,null,null,OrderStatus.FILLED).orders())
                .extracting(v->v.orderId()).containsExactly(91L);
        assertThat(repository.historyOrders(ProductLine.SPOT,7,"868",10,null,1001L,null,null,null,null).orders()).isEmpty();
        assertThat(repository.historyOrders(ProductLine.SPOT,7,"868",10,null,null,999L,null,null,null).orders()).isEmpty();
        assertThatThrownBy(()->repository.historyOrders(ProductLine.SPOT,7,null,10,null,null,null,null,null,OrderStatus.ACCEPTED)).isInstanceOf(IllegalArgumentException.class);
    }
    private void add(ProductLine line,long id,long user,String instrument,String status,long created,long updated) {
        var view=new CoreOrderStateView(id,line,user,instrument,CoreOrderSide.BUY,12345,25,0,25,false,
                CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"order-"+id,new UUID(0,id),0,0,created,updated,1,status,1);
        jdbc.update("INSERT INTO core_order_projection(product_line,order_id,user_id,client_order_id,instrument_id,status,created_at_epoch_ms,updated_at_epoch_ms,cluster_position,order_revision,export_sequence,raw_order_state) VALUES (?,?,?,?,?,?,?,?,1,1,1,?)",
                line.name(),id,user,"order-"+id,instrument,status,created,updated,CoreStateQueryCodec.encodeOrderState(view));
    }
}
