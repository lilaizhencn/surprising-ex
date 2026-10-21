package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.config.C2cRuntime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.c2c.model.C2cOrderStatus;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.c2c.utils.C2cDigests;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cHttpPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {fixture=new C2cHttpFixture();}
    @AfterEach void cleanup() {if(fixture!=null)fixture.close();}

    @Test void fullHttpOrderFlowUsesRealTransactionsAndKeepsImmutablePaymentSnapshot() throws Exception {
        assertThat(AopUtils.isAopProxy(fixture.bean(C2cAds.class))).isTrue();
        assertThat(AopUtils.isAopProxy(fixture.bean(C2cOrders.class))).isTrue();
        var publish=fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest(
                "qa-publish-"+UUID.randomUUID(),fixture.payload("SELL"),new C2cVerification("123456","")),200);
        assertThat(((Map<?,?>)publish.get("result")).get("status")).isEqualTo("RESERVING");
        verifyNoInteractions(fixture.core);
        fixture.settle();
        UUID ad=UUID.fromString((String)publish.get("resultId"));
        UUID quote=fixture.quote(ad,12,"2");
        var create=fixture.create(quote,12,"qa-create-"+UUID.randomUUID(),"",200);
        UUID order=UUID.fromString((String)create.get("resultId"));
        assertThat(((Map<?,?>)create.get("result")).get("quantityUnits")).isEqualTo("200000000");
        assertThat(((Map<?,?>)create.get("result")).get("buyerReceivesUnits")).isEqualTo("199800000");
        var account=fixture.bean(C2cPaymentAccounts.class).get(11,fixture.makerAccount,false);
        fixture.bean(C2cPaymentAccounts.class).save(11,account.id(),account.revision(),"CNY","BANK_TRANSFER",
                new C2cPaymentAccounts.Details("QA Changed","123456789012","QA Test Bank",null));
        var detail=fixture.call(HttpMethod.GET,"/api/v1/c2c/orders/"+order,12,null,200);
        var payment=(Map<?,?>)detail.get("payment");
        assertThat(payment.get("methodCode")).isEqualTo("ALIPAY");
        assertThat(payment.get("accountNumber")).isEqualTo("qa-maker-account");
        assertThat(detail).doesNotContainKeys("paymentCiphertext","payment_details_ciphertext");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/paid",12,new C2cController.PaidRequest(null),200);
        verify(fixture.core,times(1)).transferOut(any(),any());
        var release=fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/release",11,new C2cVerification("123456",""),200);
        assertThat(release.get("status")).isEqualTo("RELEASE_PENDING");
        fixture.settle();
        var completed=fixture.call(HttpMethod.GET,"/api/v1/c2c/orders/"+order,11,null,200);
        assertThat(completed.get("status")).isEqualTo("COMPLETED");
        var rejected=fixture.call(HttpMethod.GET,"/api/v1/c2c/orders/"+order,13,null,403);
        assertThat(fixture.json.writeValueAsString(rejected)).doesNotContain("qa-maker-account");
    }

    @Test void invalidFundsVerificationRollsBackOrderAndStockButKeepsAttemptsAndReceipt() throws Exception {
        UUID ad=fixture.publish("BUY");UUID quote=fixture.quote(ad,12,"2");String key="qa-rejected-"+UUID.randomUUID();
        fixture.create(quote,12,key,"000000",409);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders",Integer.class)).isZero();
        assertThat(fixture.bean(C2cAdsRepository.class).get(ad,false).availableUnits()).isEqualTo(1000000000);
        assertThat(fixture.db.jdbc.queryForObject("SELECT attempts FROM c2c_otp_attempts",Integer.class)).isEqualTo(1);
        var receipt=fixture.bean(C2cSubmissions.class).get(12,key,false);
        assertThat(receipt.status()).isEqualTo("REJECTED");
        verifyNoInteractions(fixture.core);
    }

    @Test void unknownRecoveryIsReadOnlyAndExplicitStopFencesALateCreation() throws Exception {
        UUID ad=fixture.publish("SELL");UUID quote=fixture.quote(ad,12,"2");String key="qa-stop-"+UUID.randomUUID();
        String hash=C2cDigests.sha256(quote.toString());
        var unknown=fixture.call(HttpMethod.GET,"/api/v1/c2c/submissions/"+key+"?kind=ORDER_CREATE&requestHash="+hash,12,null,200);
        assertThat(unknown.get("status")).isEqualTo("UNKNOWN");
        assertThat(fixture.bean(C2cSubmissions.class).get(12,key,false)).isNull();
        fixture.call(HttpMethod.POST,"/api/v1/c2c/submissions/"+key+"/stop",12,new C2cController.SubmissionIdentity("ORDER_CREATE",hash),200);
        var rejected=fixture.create(quote,12,key,"",200);
        assertThat(rejected.get("status")).isEqualTo("REJECTED");
        assertThat(rejected.get("resultId")).isNull();
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders",Integer.class)).isZero();
        assertThat(fixture.bean(C2cAdsRepository.class).get(ad,false).availableUnits()).isEqualTo(1000000000);
    }

    @Test void anAcceptedReceiptSurvivesLostResponseAndClosedMarketWithoutResubmission() throws Exception {
        UUID ad=fixture.publish("SELL");UUID quote=fixture.quote(ad,12,"2");String key="qa-recover-"+UUID.randomUUID();
        String hash=C2cDigests.sha256(quote.toString());var original=fixture.create(quote,12,key,"",200);
        fixture.db.jdbc.update("UPDATE c2c_configuration SET enabled=false");when(fixture.products.enabled()).thenReturn(List.of());
        var recovered=fixture.call(HttpMethod.GET,"/api/v1/c2c/submissions/"+key+"?kind=ORDER_CREATE&requestHash="+hash,12,null,200);
        assertThat(recovered.get("resultId")).isEqualTo(original.get("resultId"));
        var replay=fixture.create(quote,12,key,"",200);
        assertThat(replay.get("resultId")).isEqualTo(original.get("resultId"));
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders",Integer.class)).isEqualTo(1);
        var other=fixture.call(HttpMethod.GET,"/api/v1/c2c/submissions/"+key+"?kind=ORDER_CREATE&requestHash="+hash,13,null,200);
        assertThat(other.get("status")).isEqualTo("UNKNOWN");
        fixture.call(HttpMethod.GET,"/api/v1/c2c/submissions/"+key+"?kind=ORDER_CREATE&requestHash="+"0".repeat(64),12,null,409);
    }

    @Test void marketFiltersUseActualKycBlocksAndUnknownStatisticsStayUnknown() throws Exception {
        fixture.publish("SELL");String path="/api/v1/c2c/ads?marketId="+fixture.marketId;
        var offers=fixture.call(HttpMethod.GET,path,12,null,200);
        var items=(List<?>)offers.get("items");assertThat(items).hasSize(1);
        var merchant=(Map<?,?>)((Map<?,?>)items.getFirst()).get("merchant");
        assertThat(merchant.get("completionRate")).isNull();
        assertThat(merchant.get("completedTotal")).isEqualTo("0");
        var strict=fixture.call(HttpMethod.GET,path+"&minCompletionRate=90",12,null,200);
        assertThat((List<?>)strict.get("items")).isEmpty();
        fixture.db.jdbc.update("INSERT INTO c2c_relations(user_id,target_user_id,kind) VALUES(12,11,'BLOCK')");
        assertThat((List<?>)fixture.call(HttpMethod.GET,path,12,null,200).get("items")).isEmpty();
        fixture.db.jdbc.update("DELETE FROM c2c_relations");
        fixture.db.jdbc.update("UPDATE gateway_user_kyc_profiles SET expires_at=now()-interval '1 second' WHERE user_id=11");
        assertThat((List<?>)fixture.call(HttpMethod.GET,path,12,null,200).get("items")).isEmpty();
    }

    @Test void anonymousRequestsCannotReadPrivateDataOrCreateSubmissions() throws Exception {
        fixture.call(HttpMethod.GET,"/api/v1/c2c/payment-accounts",0,null,401);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders",0,new C2cController.OrderRequest("qa-noauth-"+UUID.randomUUID(),UUID.randomUUID(),null),401);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_submissions",Integer.class)).isZero();
        fixture.call(HttpMethod.GET,"/api/v1/c2c/orders/not-a-uuid",12,null,400);
    }

    @Test void businessClosureStillSettlesAlreadyAcceptedFundsAndNeverTimesOutPaidOrders() throws Exception {
        UUID ad=fixture.publish("SELL"),quote=fixture.quote(ad,12,"2");
        UUID order=UUID.fromString((String)fixture.create(quote,12,"qa-existing-"+UUID.randomUUID(),"",200).get("resultId"));
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/paid",12,new C2cController.PaidRequest(null),200);
        fixture.db.jdbc.update("UPDATE c2c_configuration SET enabled=false");
        fixture.db.jdbc.update("UPDATE c2c_orders SET payment_expires_at=now()-interval '1 second'");
        fixture.settle();
        assertThat(fixture.bean(C2cOrdersRepository.class).get(order,false).status()).isEqualTo(C2cOrderStatus.PAID);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/release",11,new C2cVerification("123456",""),200);
        fixture.settle();
        assertThat(fixture.bean(C2cOrdersRepository.class).get(order,false).status()).isEqualTo(C2cOrderStatus.COMPLETED);
    }

    @Test void runtimeOffDoesNotCreateControllersWorkersOrRequireTheNewTables() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("surprising.c2c.enabled","false")));
            context.register(C2cController.class,C2cRuntime.class,C2cSettlement.class);context.refresh();
            assertThat(context.getBeansOfType(C2cController.class)).isEmpty();
            assertThat(context.containsBean("c2cSettlementScheduler")).isFalse();
        }
        var markets=mock(C2cMarketService.class);var products=mock(com.surprising.gateway.provider.product.GatewayProductServices.class);
        assertThat(new C2cStatusController(false,markets,products).status().get("available")).isEqualTo(false);
        verifyNoInteractions(markets,products);
    }

    @Test void concurrentHttpBuyersCannotOversellTheSameAd() throws Exception {
        UUID ad=fixture.publish("SELL"),left=fixture.quote(ad,12,"7"),right=fixture.quote(ad,13,"7");
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->fixture.perform(HttpMethod.POST,"/api/v1/c2c/orders",12,
                    new C2cController.OrderRequest("qa-concurrent-"+UUID.randomUUID(),left,null)).getResponse().getStatus());
            var second=executor.submit(()->fixture.perform(HttpMethod.POST,"/api/v1/c2c/orders",13,
                    new C2cController.OrderRequest("qa-concurrent-"+UUID.randomUUID(),right,null)).getResponse().getStatus());
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
        } finally {executor.shutdownNow();}
        var stock=fixture.bean(C2cAdsRepository.class).get(ad,false);
        assertThat(stock.availableUnits()).isEqualTo(300000000);
        assertThat(stock.reservedUnits()).isEqualTo(700000000);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders",Integer.class)).isEqualTo(1);
    }
}
