package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.*;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.ProductAccountAdjustment;
import com.surprising.gateway.provider.service.ProductAccountClient;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cOrdersPostgresTest {
    private C2cDatabaseFixture db;
    private C2cAdsRepository adRows;
    private C2cOrdersRepository orderRows;
    private C2cFundsRepository intents;
    private C2cSubmissions requests;
    private C2cPaymentAccounts payments;
    private C2cAds ads;
    private C2cOrders orders;
    private C2cFunds funds;
    private C2cAccess access;
    private ProductAccountClient core;
    private UUID marketId,makerAccount,sellerAccount;
    private final ObjectMapper json=new ObjectMapper();
    private static JwtPrincipal user(long id) {return new JwtPrincipal(id,"qa_"+id,"NORMAL",List.of(),Instant.now().plusSeconds(600));}
    @BeforeEach void setup() throws Exception {
        db=new C2cDatabaseFixture();var jdbc=db.jdbc;
        jdbc.update("INSERT INTO gateway_users(user_id) VALUES(9),(11),(12),(13)");
        jdbc.update("INSERT INTO assets(asset,scale_units,listed,trading_enabled) VALUES('USDT',100000000,true,true)");
        marketId=UUID.randomUUID();
        jdbc.update("UPDATE c2c_configuration SET enabled=true");
        jdbc.update("""
                INSERT INTO c2c_markets(market_id,asset,fiat,fiat_decimals,enabled,min_fiat,max_fiat,min_price,max_price,buyer_fee_ppm)
                VALUES(?,'USDT','CNY',2,true,1,100000,1,100,1000)
                """,marketId);
        jdbc.update("UPDATE c2c_payment_methods SET enabled=true,currencies=ARRAY['CNY'] WHERE code='ALIPAY'");
        jdbc.update("INSERT INTO c2c_merchants(user_id,status,application_text) VALUES(11,'APPROVED','QA')");
        var properties=new GatewayProperties();properties.getSecurity().setMfaSecretEncryptionKey("c2c-fixture-encryption-key-not-for-deployment");
        payments=new C2cPaymentAccounts(jdbc,new TotpService(properties),json,null);
        makerAccount=payments.save(11,null,0,"CNY","ALIPAY",new C2cPaymentAccounts.Details("QA Seller","qa-seller-account","",null)).id();
        sellerAccount=payments.save(12,null,0,"CNY","ALIPAY",new C2cPaymentAccounts.Details("QA Taker","qa-taker-account","",null)).id();
        jdbc.update("UPDATE c2c_payment_accounts SET status='APPROVED'");
        adRows=new C2cAdsRepository(jdbc);orderRows=new C2cOrdersRepository(jdbc,json);intents=new C2cFundsRepository(jdbc);
        requests=new C2cSubmissions(jdbc);access=mock(C2cAccess.class);var markets=new C2cMarkets(jdbc);
        ads=new C2cAds(adRows,markets,payments,access,intents,jdbc,json,requests);
        orders=new C2cOrders(orderRows,adRows,markets,payments,access,intents,jdbc,requests);
        core=mock(ProductAccountClient.class);var applied=new ProductAccountAdjustment(ProductAccountAdjustment.Status.APPLIED,null);
        when(core.transferOut(any(),any())).thenReturn(applied);when(core.transferIn(any(),any())).thenReturn(applied);when(core.completeTransfer(any(),any())).thenReturn(applied);
        funds=new C2cFunds(intents,core,markets);
    }
    @AfterEach void cleanup(){if(db!=null)db.close();}
    private C2cAdsRepository.Ad ad(String side) {
        var body=new C2cAds.Publish(marketId,side,"6.65","10","1","10000",List.of("ALIPAY"),side.equals("SELL")?List.of(makerAccount):List.of(),900,"Original terms",0);
        String key="qa-ad-"+UUID.randomUUID();requests.admit(11,key,"AD_PUBLISH",C2cAccess.digest(json.writeValueAsString(body)));
        var value=db.transaction(()->ads.publish(user(11),"Bearer fixture",key,body,"123456",""));
        settle();return adRows.get(value.id(),false);
    }
    private C2cOrdersRepository.Quote quote(C2cAdsRepository.Ad ad,long actor,String quantity) {
        return db.transaction(()->orders.quote(user(actor),ad.id(),"CRYPTO",quantity,ad.side().equals("SELL")?makerAccount:sellerAccount));
    }
    private C2cOrdersRepository.Order create(C2cOrdersRepository.Quote quote,long actor,String key) {
        requests.admit(actor,key,"ORDER_CREATE",C2cAccess.digest(quote.id().toString()));
        return db.transaction(()->orders.create(user(actor),"Bearer fixture",key,quote.id(),"123456",""));
    }
    private C2cOrdersRepository.Order create(C2cAdsRepository.Ad ad) {return create(quote(ad,12,"2"),12,"qa-order-"+UUID.randomUUID());}
    private void settle() {
        for(var id:db.jdbc.query("SELECT intent_id FROM c2c_fund_intents WHERE status NOT IN ('APPLIED','REJECTED') ORDER BY created_at",(r,n)->r.getObject(1,UUID.class))) funds.resume(id);
        for(var ad:adRows.progress(64))db.transaction(()->{ads.finishFunding(ad.id());return null;});
        for(var id:orderRows.fundingReady(64))db.transaction(()->{orders.finishFunding(id);return null;});
    }

    @Test void buyerPaidNeverReleasesMoneyAndSellerReleaseKeepsFeeAndNetEqualToGross() {
        var ad=ad("SELL");var order=create(ad);
        assertThat(order.status()).isEqualTo(C2cOrderStatus.PAYMENT_PENDING);
        db.transaction(()->orders.paid(user(12),order.id(),null));
        assertThat(intents.find("ORDER",order.id(),"ORDER_RELEASE")).isNull();
        verify(core,times(1)).transferIn(any(),any());
        db.jdbc.update("UPDATE c2c_orders SET payment_expires_at=now()-interval '1 second' WHERE order_id=?",order.id());
        db.transaction(()->{orders.expire(order.id());return null;});
        assertThat(orderRows.get(order.id(),false).status()).isEqualTo(C2cOrderStatus.PAID);
        db.transaction(()->orders.release(user(11),"Bearer fixture",order.id(),"123456",""));
        var net=intents.find("ORDER",order.id(),"ORDER_RELEASE");var fee=intents.find("ORDER",order.id(),"BUYER_FEE");
        assertThat(net.amountUnits()+fee.amountUnits()).isEqualTo(order.quantityUnits());
        assertThat(net.targetUserId()).isEqualTo(12);assertThat(fee.targetUserId()).isEqualTo(2);
        assertThat(orderRows.get(order.id(),false).status()).isEqualTo(C2cOrderStatus.RELEASE_PENDING);
        settle();
        assertThat(orderRows.get(order.id(),false).status()).isEqualTo(C2cOrderStatus.COMPLETED);
        assertThat(adRows.get(ad.id(),false).completedUnits()).isEqualTo(200000000);
        db.transaction(()->orders.release(user(11),"Bearer fixture",order.id(),"",""));
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isEqualTo(3);
    }

    @Test void unpaidCancellationRestoresAlreadyEscrowedSellAdInventoryWithoutASecondRefund() {
        var ad=ad("SELL");var order=create(ad);
        assertThatThrownBy(()->db.transaction(()->orders.cancel(user(11),order.id(),"seller cancel"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->db.transaction(()->orders.cancel(user(13),order.id(),"not a participant"))).isInstanceOf(IllegalStateException.class);
        db.transaction(()->orders.cancel(user(12),order.id(),"changed payment plan"));
        assertThat(orderRows.get(order.id(),false).status()).isEqualTo(C2cOrderStatus.CANCELED);
        assertThat(adRows.get(ad.id(),false).availableUnits()).isEqualTo(1000000000);
        assertThat(intents.find("ORDER",order.id(),"ORDER_REFUND")).isNull();
        db.transaction(()->orders.cancel(user(12),order.id(),"retry"));
        assertThat(adRows.get(ad.id(),false).availableUnits()).isEqualTo(1000000000);
    }

    @Test void buyAdOnlyStartsPaymentClockAfterSellerEscrowAndExpiryWaitsForRealRefundReceipt() {
        var ad=ad("BUY");var order=create(ad);
        assertThat(order.status()).isEqualTo(C2cOrderStatus.RESERVING);assertThat(order.paymentExpiresAt()).isNull();
        settle();order=orderRows.get(order.id(),false);final var id=order.id();
        assertThat(order.status()).isEqualTo(C2cOrderStatus.PAYMENT_PENDING);assertThat(order.paymentExpiresAt()).isAfter(Instant.now());
        db.jdbc.update("UPDATE c2c_orders SET payment_expires_at=now()-interval '1 second' WHERE order_id=?",id);
        db.transaction(()->{orders.expire(id);return null;});
        assertThat(orderRows.get(id,false).status()).isEqualTo(C2cOrderStatus.REFUND_PENDING);
        assertThat(adRows.get(ad.id(),false).availableUnits()).isEqualTo(800000000);
        settle();
        assertThat(orderRows.get(id,false).status()).isEqualTo(C2cOrderStatus.EXPIRED);
        assertThat(adRows.get(ad.id(),false).availableUnits()).isEqualTo(1000000000);
    }

    @Test void quoteCannotBeStolenOrUsedAfterPriceChangeAndOrderRetainsOldPaymentSnapshot() {
        var ad=ad("SELL");var quote=quote(ad,12,"2");
        assertThatThrownBy(()->create(quote,13,"qa-stolen-order-key-001")).isInstanceOf(IllegalStateException.class);
        var order=create(quote,12,"qa-original-order-key-001");
        var oldSnapshot=order.paymentCiphertext();
        db.transaction(()->payments.save(11,makerAccount,payments.get(11,makerAccount,false).revision(),"CNY","ALIPAY",
                new C2cPaymentAccounts.Details("QA Seller","qa-replacement-account","",null)));
        assertThat(adRows.get(ad.id(),false).status()).isEqualTo("PAUSED");
        assertThat(orderRows.get(order.id(),false).paymentCiphertext()).isEqualTo(oldSnapshot);
        assertThat(payments.get(11,makerAccount,false).ciphertext()).isNotEqualTo(oldSnapshot);
        assertThat(orderRows.get(order.id(),false).terms()).isEqualTo("Original terms");
    }

    @Test void appealLocksSellerReleaseAndRefundDecisionCannotBeChangedAfterFundsIntentIsFixed() {
        var ad=ad("SELL");var order=create(ad);
        db.transaction(()->orders.paid(user(12),order.id(),null));
        assertThatThrownBy(()->db.transaction(()->orders.cancel(user(12),order.id(),"paid"))).isInstanceOf(IllegalStateException.class);
        UUID appeal=db.transaction(()->orders.appeal(user(12),order.id(),"payment dispute"));
        assertThatThrownBy(()->db.transaction(()->orders.release(user(11),"Bearer fixture",order.id(),"123456",""))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->db.transaction(()->{orders.resolveAppeal(appeal,"REFUND","self review",12,10);return null;})).isInstanceOf(IllegalStateException.class);
        db.transaction(()->{orders.resolveAppeal(appeal,"REFUND","Evidence reviewed",9,100);return null;});
        assertThat(orderRows.get(order.id(),false).status()).isEqualTo(C2cOrderStatus.REFUND_PENDING);
        assertThatThrownBy(()->db.transaction(()->{orders.resolveAppeal(appeal,"RELEASE","changed outcome",9,101);return null;})).isInstanceOf(IllegalStateException.class);
        settle();
        assertThat(orderRows.get(order.id(),false).status()).isEqualTo(C2cOrderStatus.CANCELED);
        assertThat(adRows.get(ad.id(),false).closedUnits()).isEqualTo(200000000);
        assertThat(intents.find("ORDER",order.id(),"ORDER_RELEASE")).isNull();
    }

    @Test void concurrentBuyersCannotAllocateMoreThanTheHeldAdvertisementStock() throws Exception {
        var ad=ad("SELL");var q1=quote(ad,12,"7");var q2=quote(ad,13,"7");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=List.<Callable<Boolean>>of(()->attempt(q1,12),()->attempt(q2,13));
            var result=new ArrayList<Boolean>();for(var job:pool.invokeAll(tasks))result.add(job.get());
            assertThat(result).containsExactlyInAnyOrder(true,false);
        }
        assertThat(adRows.get(ad.id(),false).reservedUnits()).isEqualTo(700000000);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders",Integer.class)).isOne();
    }
    private boolean attempt(C2cOrdersRepository.Quote q,long id) {
        try{create(q,id,"qa-concurrent-order-"+id);return true;}catch(IllegalArgumentException|IllegalStateException rejected){return false;}
    }
}
