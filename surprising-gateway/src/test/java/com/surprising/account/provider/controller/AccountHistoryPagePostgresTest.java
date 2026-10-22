package com.surprising.account.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.*;
import com.surprising.account.provider.service.*;
import com.surprising.gateway.provider.local.AccountLocalRoutes;
import com.surprising.gateway.provider.local.LocalApiRequest;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class AccountHistoryPagePostgresTest {
    final long first=9007199254740993L;
    JdbcTemplate admin,jdbc;String schema;
    AccountLedgerRepository basic;ProductLedgerRepository product;ProductTransferRepository transfers;
    AdminBalanceAdjustmentRepository adjustments;
    final AccountService live=mock(AccountService.class);
    final AccountCommandGateway commands=mock(AccountCommandGateway.class);
    ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL"),user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="account_page_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);jdbc=new JdbcTemplate(new DriverManagerDataSource(
                url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password));
        String init=Files.readString(Path.of("../init.sql"));
        for(String table:List.of("account_ledger_entries","account_product_ledger_entries","account_product_transfers","account_admin_balance_adjustments")) {
            int start=init.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
            jdbc.execute(init.substring(start,init.indexOf("\n);",start)+3));
        }
        basic=spy(new AccountLedgerRepository(jdbc));product=spy(new ProductLedgerRepository(jdbc));
        transfers=spy(new ProductTransferRepository(jdbc));adjustments=spy(new AdminBalanceAdjustmentRepository(jdbc));
    }
    @AfterEach void cleanup() {if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}

    @ParameterizedTest @EnumSource(ProductLine.class)
    void actualHistoryEntrancesKeepUserAccountFiltersAndOddLongCursorsForEachProduct(ProductLine line) throws Exception {
        seed(line);var requests=requests(line);var mvc=MockMvcBuilders.standaloneSetup(new AccountInternalController(requests)).build();
        for(String path:List.of("ledger","product-ledger","transfers"))for(String sort:List.of("createdAt.asc","createdAt.desc")) {
            String rows=path.equals("transfers")?"transfers":"entries";
            var firstPage=read(mvc,path,line,sort,null);
            long expected=sort.endsWith("asc")?first:first+2;
            String id=path.equals("transfers")?"transferId":"entryId";
            assertThat(firstPage.path(rows).get(0).path(id).asLong()).isEqualTo(expected);
            assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
            var next=read(mvc,path,line,sort,firstPage.path("nextCursor").asString());
            assertThat(next.path(rows).get(0).path(id).asLong()).isEqualTo(sort.endsWith("asc")?first+2:first);
            assertThat(next.path(rows).get(0).path("userId").asLong()).isEqualTo(42L);
            assertThat(next.path("hasMore").asBoolean()).isFalse();
        }
        var headers=new HttpHeaders();headers.set("X-Admin-User-Id","7");
        var routes=new AccountLocalRoutes(requests);
        String path="/api/v1/admin/accounts/adjustments?adminUserId=7&userId=42&accountType="+line.accountTypeCode()
                +"&asset=usdt&adjustmentKind=product&limit=1";
        JsonNode firstAdjustment=json.valueToTree(routes.invoke(new LocalApiRequest(URI.create(path),HttpMethod.GET,headers,null,json,null)));
        assertThat(firstAdjustment.path("adjustments").get(0).path("adjustmentId").asLong()).isEqualTo(first+2);
        String cursor=java.net.URLEncoder.encode(firstAdjustment.path("nextCursor").asString(),java.nio.charset.StandardCharsets.UTF_8);
        JsonNode next=json.valueToTree(routes.invoke(new LocalApiRequest(URI.create(path+"&cursor="+cursor),HttpMethod.GET,headers,null,json,null)));
        assertThat(next.path("adjustments").get(0).path("adjustmentId").asLong()).isEqualTo(first);
        assertThat(next.path("hasMore").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT sum(amount_units) FROM account_product_transfers",Long.class)).isEqualTo(400L);
        verifyNoInteractions(live,commands);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void productMismatchAndFundingNeverReachProductHistoryRepository(ProductLine line) throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new AccountInternalController(requests(line))).build();
        var wrong=line==ProductLine.SPOT?AccountType.OPTION:AccountType.SPOT;
        for(AccountType type:List.of(wrong,AccountType.FUNDING)) {
            mvc.perform(get("/api/v1/accounts/product-ledger").header("X-User-Id","42").param("accountType",type.name()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(product,live,commands);
    }

    @ParameterizedTest @ValueSource(strings={"low-limit","high-limit","sort","asset","reference","cursor","time","user"})
    void malformedHistoryQueryFailsBeforeAnyRepository(String invalid) throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new AccountInternalController(requests(ProductLine.SPOT))).build();
        var request=get("/api/v1/accounts/ledger").header("X-User-Id",invalid.equals("user")?"-1":"42");
        switch(invalid) {
            case "low-limit" -> request.param("limit","0");
            case "high-limit" -> request.param("limit","1001");
            case "sort" -> request.param("sort","createdAt DESC; DROP TABLE account_ledger_entries");
            case "asset" -> request.param("asset","US-DT");
            case "reference" -> request.param("referenceType","invalid reference");
            case "cursor" -> request.param("cursor","***");
            case "time" -> request.param("cursor",java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("invalid-time|1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            default -> { }
        }
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(basic,product,transfers,adjustments,live,commands);
    }

    private AccountRequestHandler requests(ProductLine line) {
        var properties=new AccountProperties();properties.getKafka().setProductLine(line);
        return new AccountRequestHandler(live,new AccountQueryService(basic,product,transfers,adjustments,properties),commands);
    }
    private JsonNode read(MockMvc mvc,String path,ProductLine line,String sort,String cursor) throws Exception {
        var request=get("/api/v1/accounts/"+path).header("X-User-Id","42").param("userId","99")
                .param("asset","usdt").param("referenceType","adjustment").param("accountType",line.accountTypeCode())
                .param("limit","1").param("sort",sort);
        if(cursor!=null)request.param("cursor",cursor);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private void seed(ProductLine line) {
        var time=Timestamp.from(Instant.parse("2026-10-21T07:00:00.123456Z"));String type=line.accountTypeCode();
        String other=line==ProductLine.SPOT?AccountType.OPTION.name():AccountType.SPOT.name();
        for(int n=0;n<4;n++) {
            long id=first+(n<2?n*2:100+n);long user=n==2?99:42;String rowType=n==3?other:type;
            jdbc.update("INSERT INTO account_ledger_entries(entry_id,user_id,asset,amount_units,balance_after_units,reference_type,reference_id,created_at) VALUES(?,?,'USDT',100,1000,'ADJUSTMENT',?,?)",id,user,"ref-"+n,time);
            jdbc.update("INSERT INTO account_product_ledger_entries(entry_id,user_id,account_type,asset,amount_units,balance_after_units,reference_type,reference_id,created_at) VALUES(?,? ,?,'USDT',100,1000,'ADJUSTMENT',?,?)",id,user,rowType,"ref-"+n,time);
            jdbc.update("INSERT INTO account_product_transfers(transfer_id,user_id,source_account_type,target_account_type,asset,amount_units,reference_id,status,created_at,updated_at) VALUES(?,?,?,'FUNDING','USDT',100,?,'COMPLETED',?,?)",id,user,rowType,"ref-"+n,time,time);
            jdbc.update("INSERT INTO account_admin_balance_adjustments(adjustment_id,reference_key,adjustment_kind,admin_user_id,user_id,account_type,asset,amount_units,balance_after_units,reference_id,created_at) VALUES(?,?,'PRODUCT',7,?,?,'USDT',100,1000,?,?)",id,"ref-"+n,user,rowType,"ref-"+n,time);
        }
        // Basic account history has no product-account filter; the fourth row uses another reference category.
        jdbc.update("UPDATE account_ledger_entries SET reference_type='OTHER' WHERE entry_id=?",first+103);
    }
}
