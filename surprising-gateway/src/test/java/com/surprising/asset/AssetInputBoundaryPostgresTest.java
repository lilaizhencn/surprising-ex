package com.surprising.asset;

import com.surprising.asset.controller.AssetConfigurationController;
import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.*;
import com.surprising.asset.service.AssetConfigurationService;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.service.WalletFundingService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class AssetInputBoundaryPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    MockMvc mvc;
    WalletFundingService funding;
    final ObjectMapper json=new ObjectMapper();
    String code;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));jdbc=new JdbcTemplate(source);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(AssetConfigurationService.class,()->new AssetConfigurationService(new AssetRepository(jdbc),new AssetNetworkRepository(jdbc),new AssetConfigurationChangeRepository(jdbc),json));context.refresh();
        var auth=mock(AuthService.class);var approvals=mock(AdminApprovalService.class);when(approvals.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
        when(approvals.requireWrite(anyString(),eq("admin.wallet.write"),eq("gateway-admin"),any(),any())).thenReturn(new JwtPrincipal(42,"operator","NORMAL",List.of("ADMIN"),Instant.now().plusSeconds(60)));
        funding=mock(WalletFundingService.class);mvc=MockMvcBuilders.standaloneSetup(new AssetConfigurationController(auth,context.getBean(AssetConfigurationService.class),approvals,json,funding)).build();
        code="MVC"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
    }
    @AfterEach void close() {if(context!=null)context.close();}
    Map<String,Object> asset() {return new HashMap<>(Map.of("asset",code,"displayName","币种","logoUrl","","scaleUnits",100000000L,"listed",true,"tradingEnabled",true,"revision",0,"reason","create"));}
    Map<String,Object> network() {return new HashMap<>(Map.ofEntries(Map.entry("networkCode","ETH"),Map.entry("displayName","Ethereum"),Map.entry("contractAddress","contract"),Map.entry("nativeAsset",false),Map.entry("chainDecimals",6),Map.entry("depositEnabled",true),Map.entry("withdrawalEnabled",true),Map.entry("minDeposit","0.01"),Map.entry("minWithdrawal","1"),Map.entry("withdrawalFee","0.1"),Map.entry("confirmations",12),Map.entry("revision",0),Map.entry("reason","network")));}
    int create() throws Exception {mvc.perform(post("/api/v1/admin/assets").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(asset()))).andExpect(status().isOk());return jdbc.queryForObject("SELECT asset_id FROM assets WHERE asset=?",Integer.class,code);}
    @ParameterizedTest @ValueSource(strings={"asset|bad-code","asset|badcode","asset| AA","displayName| ","logoUrl|http://unsafe","reason| "})
    void malformedAssetNeverWritesConfigurationOrAudit(String value) throws Exception {
        long count=jdbc.queryForObject("SELECT count(*) FROM asset_configuration_changes",Long.class);var input=asset();String[] pair=value.split("\\|",2);input.put(pair[0],pair[1]);
        mvc.perform(post("/api/v1/admin/assets").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_configuration_changes",Long.class)).isEqualTo(count);verifyNoInteractions(funding);
    }
    @ParameterizedTest @ValueSource(strings={"networkCode|bad/network","networkCode|eth","networkCode| ETH","displayName| ","contractAddress| contract","confirmations|0","chainDecimals|37","reason| "})
    void malformedNetworkNeverReachesFundingOrPersistence(String value) throws Exception {
        int id=create();long count=jdbc.queryForObject("SELECT count(*) FROM asset_configuration_changes WHERE asset_id=?",Long.class,id);var input=network();String[] pair=value.split("\\|",2);input.put(pair[0],pair[0].equals("confirmations")||pair[0].equals("chainDecimals")?Integer.valueOf(pair[1]):pair[1]);
        mvc.perform(post("/api/v1/admin/assets/"+id+"/networks").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input))).andExpect(status().isBadRequest());
        verifyNoInteractions(funding);assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_networks WHERE asset_id=?",Integer.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_configuration_changes WHERE asset_id=?",Long.class,id)).isEqualTo(count);
    }
    @Test void nativeIdentityAndPrecisionRulesStillRunUnderAssetLock() throws Exception {
        int id=create();var input=asset();input.put("assetId",id);input.put("scaleUnits",1000L);input.put("revision",1);
        mvc.perform(post("/api/v1/admin/assets").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT scale_units FROM assets WHERE asset_id=?",Long.class,id)).isEqualTo(100000000L);
        var config=network();config.put("minDeposit","0.000000001");mvc.perform(post("/api/v1/admin/assets/"+id+"/networks").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(config))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_networks WHERE asset_id=?",Integer.class,id)).isZero();
    }
    @Test void auditFailureRollsBackConfigurationMutation() throws Exception {
        jdbc.execute("ALTER TABLE asset_configuration_changes ADD CONSTRAINT mvc_asset_audit_fault CHECK(reason<>'mvc audit fault') NOT VALID");
        try {var input=asset();input.put("reason","mvc audit fault");mvc.perform(post("/api/v1/admin/assets").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input))).andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM assets WHERE asset=?",Integer.class,code)).isZero();
        } finally {jdbc.execute("ALTER TABLE asset_configuration_changes DROP CONSTRAINT mvc_asset_audit_fault");}
    }
}
