package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.c2c.service.C2cSubmissionService;
import com.surprising.gateway.c2c.utils.C2cDigests;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cSubmissionBoundaryPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {fixture=new C2cHttpFixture();}
    @AfterEach void cleanup() {if(fixture!=null) fixture.close();}

    @Test void callerRollbackCannotEraseAnAcceptedAdAndItsOriginalFundingIntent() {
        String key="qa-outer-ad-"+UUID.randomUUID();var payload=fixture.payload("SELL");String hash=hash(payload);
        assertThatThrownBy(()->fixture.db.transaction(()->{
            fixture.bean(C2cSubmissionService.class).publish(C2cHttpFixture.principal(11),"Bearer qa-11",key,hash,
                    C2cController.publishParameters(payload),"123456","");throw new IllegalStateException("Synthetic caller rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Synthetic caller rollback");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_submissions WHERE user_id=11 AND request_key=? AND status='SUCCEEDED'",Long.class,key)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_ads WHERE status='RESERVING'",Long.class)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE purpose='AD_RESERVE'",Long.class)).isEqualTo(1);verifyNoInteractions(fixture.core);
    }

    @Test void callerRollbackCannotEraseAnAcceptedOrderOrItsEvent() throws Exception {
        UUID ad=fixture.publish("SELL"),quote=fixture.quote(ad,12,"1");String key="qa-outer-order-"+UUID.randomUUID();String hash=C2cDigests.sha256(quote.toString());
        clearInvocations(fixture.core);
        assertThatThrownBy(()->fixture.db.transaction(()->{
            fixture.bean(C2cSubmissionService.class).create(C2cHttpFixture.principal(12),"Bearer qa-12",key,quote,hash,"","");
            throw new IllegalStateException("Synthetic caller rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Synthetic caller rollback");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_submissions WHERE user_id=12 AND request_key=? AND status='SUCCEEDED'",Long.class,key)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders WHERE quote_id=?",Long.class,quote)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_order_events WHERE event_type='ORDER_CREATED'",Long.class)).isEqualTo(1);verifyNoInteractions(fixture.core);
    }

    @Test void anExplicitStopRemainsDurableIfAHigherLevelCallerRollsBack() {
        String key="qa-outer-stop-"+UUID.randomUUID(),hash="0".repeat(64);
        assertThatThrownBy(()->fixture.db.transaction(()->{
            fixture.bean(C2cSubmissionService.class).stop(12,key,"ORDER_CREATE",hash);throw new IllegalStateException("Synthetic caller rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_submissions WHERE user_id=12 AND request_key=? AND error_code='REQUEST_STOPPED'",Long.class,key)).isEqualTo(1);
    }

    @Test void stoppedMalformedPayloadReplaysItsOriginalRejectionWithoutValidationSideEffects() throws Exception {
        var payload=invalidPayload();String key="qa-stopped-format-"+UUID.randomUUID(),hash=hash(payload);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/submissions/"+key+"/stop",11,new C2cController.SubmissionIdentity("AD_PUBLISH",hash),200);
        var replay=fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest(key,payload,null),200);
        assertThat(replay.get("status")).isEqualTo("REJECTED");assertThat(replay.get("errorCode")).isEqualTo("REQUEST_STOPPED");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_ads",Long.class)).isZero();verifyNoInteractions(fixture.security,fixture.core);
    }

    @Test void firstMalformedPayloadLeavesARejectionAndSameKeyCannotSwapTheOriginalBody() throws Exception {
        var payload=invalidPayload();String key="qa-rejected-format-"+UUID.randomUUID(),hash=hash(payload);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest(key,payload,null),400);
        var receipt=fixture.call(HttpMethod.GET,"/api/v1/c2c/submissions/"+key+"?kind=AD_PUBLISH&requestHash="+hash,11,null,200);
        assertThat(receipt.get("status")).isEqualTo("REJECTED");assertThat(receipt.get("errorCode")).isEqualTo("VALIDATION_REJECTED");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest(key,fixture.payload("SELL"),new C2cVerification("123456","")),409);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_ads",Long.class)).isZero();verifyNoInteractions(fixture.security,fixture.core);
    }

    @Test void retryableAvailabilityFailureKeepsTheOriginalProcessingReceipt() throws Exception {
        String key="qa-retryable-"+UUID.randomUUID();var payload=fixture.payload("SELL");
        when(fixture.products.enabled()).thenReturn(List.of());
        fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest(key,payload,new C2cVerification("123456","")),503);
        assertThat(fixture.bean(C2cSubmissions.class).get(11,key,false).status()).isEqualTo("PROCESSING");
        when(fixture.products.enabled()).thenReturn(List.of(ProductLine.SPOT));
        assertThat(fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest(key,payload,new C2cVerification("123456","")),200).get("status")).isEqualTo("SUCCEEDED");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Long.class)).isEqualTo(1);verifyNoInteractions(fixture.core);
    }

    @Test void verifiedDisableRollsBackAnEarlierAdPauseWhenTheAccountWriteFails() throws Exception {
        UUID ad=fixture.publish("SELL");var before=fixture.bean(C2cAdsRepository.class).get(ad,false);
        fixture.db.jdbc.execute("ALTER TABLE c2c_payment_accounts ADD CONSTRAINT reject_disabled CHECK(status<>'DISABLED')");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/payment-accounts/"+fixture.makerAccount+"/disable",11,
                new C2cController.PaymentDisable("1",new C2cVerification("123456","")),409);
        assertThat(fixture.bean(C2cAdsRepository.class).get(ad,false)).isEqualTo(before);
        assertThat(fixture.bean(C2cPaymentAccounts.class).get(11,fixture.makerAccount,false).status()).isEqualTo("APPROVED");
    }
    private String hash(Object payload) {return C2cDigests.sha256(fixture.json.writeValueAsString(payload));}
    private C2cController.Publish invalidPayload() {
        var p=fixture.payload("SELL");return new C2cController.Publish(p.marketId(),p.side(),"1e3",p.quantity(),p.minFiat(),p.maxFiat(),
                p.methodCodes(),p.paymentAccountIds(),p.paymentWindowSeconds(),p.terms(),p.minCompleted());
    }
}
