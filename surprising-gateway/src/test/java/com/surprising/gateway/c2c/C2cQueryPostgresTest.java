package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;
import com.surprising.gateway.c2c.repository.C2cSubmissions;
import com.surprising.gateway.c2c.service.C2cPaymentAccounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.gateway.c2c.model.C2cUserOrderFilter;
import com.surprising.gateway.c2c.service.C2cQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cQueryPostgresTest {
    private C2cHttpFixture fixture;
    private CountingJdbc jdbc;
    private C2cQueryService queries;

    @BeforeEach void setup() throws Exception {
        fixture=new C2cHttpFixture();
        jdbc=new CountingJdbc(fixture.db.jdbc.getDataSource());
        queries=C2cDatabaseFixture.queries(jdbc,new C2cAdsRepository(jdbc),new C2cOrdersRepository(jdbc),
                C2cDatabaseFixture.markets(jdbc),fixture.bean(C2cPaymentAccounts.class),fixture.json,new C2cSubmissions(jdbc));
    }
    @AfterEach void cleanup() {if(fixture!=null) fixture.close();}

    @Test void ownedPagesBatchMarketMetadataAndNeverExposePaymentSnapshots() throws Exception {
        UUID ad=fixture.publish("SELL");fixture.publish("BUY");
        for(int i=0;i<3;i++) fixture.create(fixture.quote(ad,12,"1"),12,"qa-own-page-"+UUID.randomUUID(),"",200);
        jdbc.selects=0;
        assertThat(queries.myAds(11,100)).hasSize(2);
        assertThat(jdbc.selects).isEqualTo(2);
        jdbc.selects=0;
        var page=queries.myOrders(12,filter(2,0));
        assertThat((List<?>)page.get("items")).hasSize(2);
        assertThat(page.get("hasMore")).isEqualTo(true);
        assertThat(jdbc.selects).isEqualTo(2);
        String first=(String)((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("orderId");
        var next=queries.myOrders(12,filter(2,2));
        assertThat((List<?>)next.get("items")).singleElement().satisfies(item ->
                assertThat(((Map<?,?>)item).get("orderId")).isNotEqualTo(first));
        assertThat(next.get("hasMore")).isEqualTo(false);
        assertThat(fixture.json.writeValueAsString(page)).doesNotContain("ciphertext","accountNumber","holderName");
        jdbc.selects=0;
        assertThat((List<?>)queries.myOrders(13,filter(30,0)).get("items")).isEmpty();
        assertThat(jdbc.selects).isEqualTo(1);
        jdbc.selects=0;
        assertThat(queries.myAds(13,100)).isEmpty();
        assertThat(jdbc.selects).isEqualTo(1);
    }

    @Test void quickQuoteRollsBackAnIneligibleCandidateBeforeTryingTheNext() throws Exception {
        UUID first=fixture.publish("SELL"),second=fixture.publish("SELL");
        var row=fixture.bean(C2cAdsRepository.class).get(first,false);
        fixture.call(HttpMethod.PATCH,"/api/v1/c2c/ads/"+first,11,new C2cController.AdEdit(
                Long.toString(row.revision()),"6.50","1","10000","Synthetic minimum history",1),200);
        var before=fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Long.class);
        clearInvocations(fixture.core);

        var quote=fixture.call(HttpMethod.POST,"/api/v1/c2c/quotes/quick",12,
                new C2cController.QuickQuoteRequest(fixture.marketId,"BUY","6.65","ALIPAY",null),200);

        assertThat(quote.get("adId")).isEqualTo(second.toString());
        assertThat(quote.get("valid")).isEqualTo(true);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_quotes",Long.class)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders",Long.class)).isZero();
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Long.class)).isEqualTo(before);
        assertThat(fixture.bean(C2cAdsRepository.class).get(second,false).availableUnits()).isEqualTo(1000000000);
        verifyNoInteractions(fixture.core);
    }

    @Test void marketPrecisionAndVipEligibilityRemainBusinessGuards() throws Exception {
        fixture.publish("SELL");
        String path="/api/v1/c2c/ads?marketId="+fixture.marketId;
        fixture.call(HttpMethod.GET,path+"&amount=1.001",12,null,400);
        fixture.call(HttpMethod.GET,path+"&vipOnly=true",0,null,403);
        fixture.call(HttpMethod.GET,path+"&vipOnly=true",12,null,403);
        var detail=fixture.call(HttpMethod.GET,"/api/v1/c2c/merchants/11",12,null,200);
        assertThat(detail.get("completedTotal")).isEqualTo("0");
        assertThat(detail.get("completionRate")).isNull();
    }

    private static C2cUserOrderFilter filter(int limit,int offset) {
        return new C2cUserOrderFilter(C2cUserOrderFilter.Scope.ALL,null,C2cUserOrderFilter.Side.ALL,null,limit,offset);
    }
    private static final class CountingJdbc extends JdbcTemplate {
        private int selects;
        CountingJdbc(DataSource source) {super(source);}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            selects++;return super.query(sql,mapper,args);
        }
    }
}
