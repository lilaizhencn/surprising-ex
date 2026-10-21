package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.c2c.model.C2cAd;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.c2c.utils.C2cDigests;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.service.ProductAccountAdjustment;
import com.surprising.gateway.provider.service.ProductAccountClient;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cAdsPostgresTest {
    private C2cDatabaseFixture db;
    private C2cAdsRepository rows;
    private C2cFundsRepository intents;
    private C2cAds ads;
    private C2cAccess access;
    private C2cFunds funds;
    private UUID marketId,paymentId;
    private final JwtPrincipal owner=new JwtPrincipal(11,"seller","NORMAL",List.of(),Instant.now().plusSeconds(60));
    @BeforeEach void setup() throws Exception {
        db=new C2cDatabaseFixture();var jdbc=db.jdbc;
        jdbc.update("INSERT INTO gateway_users(user_id,username) VALUES(11,'seller'),(12,'other')");
        jdbc.update("INSERT INTO assets(asset,scale_units,listed,trading_enabled) VALUES('USDT',100000000,true,true)");
        marketId=UUID.randomUUID();paymentId=UUID.randomUUID();
        jdbc.update("UPDATE c2c_configuration SET enabled=true");
        jdbc.update("""
                INSERT INTO c2c_markets(market_id,asset,fiat,fiat_decimals,enabled,min_fiat,max_fiat,min_price,max_price)
                VALUES (?,'USDT','CNY',2,true,1,100000,1,100)
                """,marketId);
        jdbc.update("UPDATE c2c_payment_methods SET enabled=true,currencies=ARRAY['CNY'] WHERE code='ALIPAY'");
        jdbc.update("INSERT INTO c2c_merchants(user_id,status,application_text) VALUES(11,'APPROVED','QA fixture')");
        db.seedMerchantBond(11);
        jdbc.update("""
                INSERT INTO c2c_payment_accounts(account_id,user_id,fiat,method_code,details_ciphertext,masked_label,status)
                VALUES(?,11,'CNY','ALIPAY','fixture ciphertext','****0000','APPROVED')
                """,paymentId);
        rows=new C2cAdsRepository(jdbc);intents=new C2cFundsRepository(jdbc);access=mock(C2cAccess.class);
        ads=C2cDatabaseFixture.ads(jdbc,rows,C2cDatabaseFixture.markets(jdbc),C2cDatabaseFixture.payments(jdbc,null,null,null),access,intents,new ObjectMapper(),new C2cSubmissions(jdbc));
        var accounts=mock(ProductAccountClient.class);
        var applied=new ProductAccountAdjustment(ProductAccountAdjustment.Status.APPLIED,null);
        when(accounts.transferOut(any(),any())).thenReturn(applied);
        when(accounts.transferIn(any(),any())).thenReturn(applied);
        when(accounts.completeTransfer(any(),any())).thenReturn(applied);
        funds=new C2cFunds(intents,accounts,C2cDatabaseFixture.markets(jdbc));
    }
    @AfterEach void cleanup() {if(db!=null)db.close();}
    private C2cAds.Publish request() {return new C2cAds.Publish(marketId,"SELL","6.65","10","1","10000",List.of("ALIPAY"),List.of(paymentId),900,"QA terms",0);}
    private C2cAd publish() {
        new C2cSubmissions(db.jdbc).admit(owner.userId(),"original-ad-request-0001","AD_PUBLISH",C2cDigests.sha256(new ObjectMapper().writeValueAsString(request())));
        return db.transaction(()->ads.publish(owner,"Bearer fixture","original-ad-request-0001",request(),"123456",""));}
    private C2cAd active() {
        var ad=publish();var intent=db.jdbc.queryForObject("SELECT intent_id FROM c2c_fund_intents WHERE owner_id=?",UUID.class,ad.id());
        funds.resume(intent);db.transaction(()->{ads.finishFunding(ad.id());return null;});return rows.get(ad.id(),false);
    }

    @Test void publishingPersistsOneIntentBeforeTheAdCanAcceptOrdersAndRetryNeedsNoNewVerification() {
        var ad=publish();assertThat(ad.status()).isEqualTo("RESERVING");
        assertThat(publish().id()).isEqualTo(ad.id());
        verify(access,times(1)).verifyFunds(any(),any(),any(),any());
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isOne();
        assertThatThrownBy(()->db.transaction(()->{rows.allocate(rows.get(ad.id(),true),1);return null;})).isInstanceOf(IllegalStateException.class);
        var intent=db.jdbc.queryForObject("SELECT intent_id FROM c2c_fund_intents WHERE owner_id=?",UUID.class,ad.id());
        assertThat(funds.resume(intent).status()).isEqualTo("APPLIED");
        db.transaction(()->{ads.finishFunding(ad.id());return null;});
        assertThat(rows.get(ad.id(),false).status()).isEqualTo("ACTIVE");
    }

    @Test void failedSecurityVerificationRollsBackAdAndItsFundIntent() {
        doThrow(new IllegalStateException("verification failed")).when(access).verifyFunds(any(),any(),any(),any());
        assertThatThrownBy(this::publish).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_ads",Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isZero();
    }

    @Test void concurrentAllocationCannotOversellAndCloseRefundsOnlyUnallocatedStock() throws Exception {
        var ad=active();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> reserve=()->{
                try {db.transaction(()->{rows.allocate(rows.get(ad.id(),true),700000000);return null;});return true;}
                catch(IllegalStateException rejected){return false;}
            };
            var results=pool.invokeAll(List.of(reserve,reserve));
            assertThat(results.stream().map(f->{try{return f.get();}catch(Exception ex){throw new RuntimeException(ex);}}).toList()).containsExactlyInAnyOrder(true,false);
        }
        var held=rows.get(ad.id(),false);
        assertThat(held.availableUnits()).isEqualTo(300000000);
        assertThat(held.reservedUnits()).isEqualTo(700000000);
        var closing=db.transaction(()->ads.status(owner,"Bearer fixture",ad.id(),held.revision(),"CLOSE","123456",""));
        assertThat(closing.status()).isEqualTo("CLOSING");
        assertThat(closing.reservedUnits()).isEqualTo(700000000);
        assertThat(db.jdbc.queryForObject("SELECT amount_units FROM c2c_fund_intents WHERE purpose='AD_REFUND'",Long.class)).isEqualTo(300000000);
        assertThat(closing.availableUnits()+closing.reservedUnits()+closing.completedUnits()+closing.closedUnits()).isEqualTo(closing.totalUnits());
    }

    @Test void otherUsersCannotCloseAdsAndSameKeyCannotChangeOriginalAmounts() {
        var ad=active();
        var other=new JwtPrincipal(12,"other","NORMAL",List.of(),Instant.now().plusSeconds(60));
        assertThatThrownBy(()->db.transaction(()->ads.status(other,"Bearer other",ad.id(),ad.revision(),"CLOSE","",""))).isInstanceOf(IllegalStateException.class);
        var changed=new C2cAds.Publish(marketId,"SELL","6.65","11","1","10000",List.of("ALIPAY"),List.of(paymentId),900,"QA terms",0);
        assertThatThrownBy(()->db.transaction(()->ads.publish(owner,"Bearer fixture","original-ad-request-0001",changed,"",""))).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isOne();
        assertThat(rows.get(ad.id(),false).status()).isEqualTo("ACTIVE");
    }
}
