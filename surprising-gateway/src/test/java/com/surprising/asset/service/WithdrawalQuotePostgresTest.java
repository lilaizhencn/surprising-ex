package com.surprising.asset.service;

import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.*;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SURPRISING_ASSET_IT_DATABASE_URL",matches=".+")
class WithdrawalQuotePostgresTest {
    private AnnotationConfigApplicationContext context;
    private AssetConfigurationService service;
    private TransactionTemplate transaction;
    private Asset asset;
    private Network network;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup() {
        String url=System.getenv("SURPRISING_ASSET_IT_DATABASE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:5432/surprising_exchange_test_[a-zA-Z0-9_]+"))throw new IllegalArgumentException("isolated local test database required");
        var ds=new DriverManagerDataSource(url,System.getenv().getOrDefault("SURPRISING_ASSET_IT_DATABASE_USER","atomex"),"");
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(ds));
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(ds));
        context.registerBean(tools.jackson.databind.ObjectMapper.class,()->new tools.jackson.databind.ObjectMapper());
        context.registerBean(AssetRepository.class);context.registerBean(AssetNetworkRepository.class);context.registerBean(AssetConfigurationChangeRepository.class);context.registerBean(AssetConfigurationService.class);context.refresh();
        service=context.getBean(AssetConfigurationService.class);transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        asset=service.save(new AssetRequest(null,"WQ"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),"Quote fixture","",1_000_000,true,true,0,"synthetic quote test"),1);
        network=service.saveNetwork(asset.assetId(),request(null,0,"0.25",true),1);
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private NetworkRequest request(Integer id,long revision,String fee,boolean enabled) {
        return new NetworkRequest(id,"ETH","Ethereum","test-contract",false,6,true,enabled,BigDecimal.ONE,BigDecimal.ONE,new BigDecimal(fee),12,revision,"synthetic fee configuration");
    }
    @Test void savedAdminFeeTakesEffectAndItsRevisionChangesWithTheNextQuotation() {
        var first=service.withdrawalQuote(asset.asset(),"ETH","25");
        assertThat(first.fee()).isEqualTo("0.25");assertThat(first.receivedAmount()).isEqualTo("24.75");
        var changed=service.saveNetwork(asset.assetId(),request(network.networkId(),network.revision(),"0.5",true),1);
        var second=service.withdrawalQuote(asset.asset(),"ETH","25");
        assertThat(second.feeUnits()).isEqualTo(500_000L);assertThat(second.receivedUnits()).isEqualTo(24_500_000L);
        assertThat(second.networkRevision()).isEqualTo(changed.revision()).isGreaterThan(first.networkRevision());
        assertThatThrownBy(()->service.saveNetwork(asset.assetId(),request(network.networkId(),network.revision(),"0.75",true),1)).isInstanceOf(IllegalStateException.class);
    }
    @Test void savedDisableAndMinimumAreEnforcedBeforeAnyWithdrawal() {
        assertThatThrownBy(()->service.withdrawalQuote(asset.asset(),"ETH","0.99")).isInstanceOf(IllegalArgumentException.class);
        service.saveNetwork(asset.assetId(),request(network.networkId(),network.revision(),"0.25",false),1);
        assertThatThrownBy(()->service.withdrawalQuote(asset.asset(),"ETH","25")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("disabled");
    }
    @Test void feeConfigurationCannotChangeWhileItsIntentSnapshotIsBeingCommitted() throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var original=pool.submit(()->transaction.execute(status->{var quote=service.lockWithdrawalQuote(asset.asset(),"ETH","25");held.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}return quote;}));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var change=pool.submit(()->service.saveNetwork(asset.assetId(),request(network.networkId(),network.revision(),"0.5",true),1));
            assertThatThrownBy(()->change.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();assertThat(original.get(5,TimeUnit.SECONDS).fee()).isEqualTo("0.25");
            assertThat(change.get(5,TimeUnit.SECONDS).withdrawalFee()).isEqualByComparingTo("0.5");
        }finally{release.countDown();pool.shutdownNow();}
    }
}
