package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.controller.C2cMeController;
import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.service.C2cAccess;

import com.surprising.gateway.c2c.service.C2cNotificationService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cMeHttpPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {fixture=new C2cHttpFixture();}
    @AfterEach void cleanup() {if(fixture!=null)fixture.close();}

    @Test void myC2cShowsRealEmptyCountsWithoutCallingCoreOrLeakingAccountDetails() throws Exception {
        var me=fixture.call(HttpMethod.GET,"/api/v1/c2c/me",12,null,200);
        assertThat(me.get("userId")).isEqualTo("12");
        assertThat(me.get("kycVerified")).isEqualTo(true);
        assertThat(((Map<?,?>)me.get("statistics")).get("completed")).isEqualTo("0");
        assertThat((List<?>)me.get("escrow")).isEmpty();
        assertThat(fixture.json.writeValueAsString(me)).doesNotContain("qa-taker-account","ciphertext","email");
        verifyNoInteractions(fixture.core);
    }

    @Test void changingVerificationWindowRequiresItsOwnCodeAndInvalidatesAllCachedGrants() throws Exception {
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",12,new C2cMeController.Settings("1","QA Buyer",false,5,new C2cVerification("000000","")),409);
        assertThat(fixture.db.jdbc.queryForObject("SELECT verification_minutes FROM c2c_profiles WHERE user_id=12",Integer.class)).isZero();
        assertThat(fixture.db.jdbc.queryForObject("SELECT attempts FROM c2c_otp_attempts",Integer.class)).isEqualTo(1);
        var changed=fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",12,new C2cMeController.Settings("1","QA Buyer",false,5,new C2cVerification("123456","")),200);
        assertThat(changed.get("revision")).isEqualTo("2");
        fixture.bean(C2cAccess.class).verifyFunds(C2cHttpFixture.principal(12),"Bearer qa-12","123456","");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_security_grants WHERE user_id=12",Integer.class)).isEqualTo(1);
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",12,new C2cMeController.Settings("2","QA Buyer",false,15,new C2cVerification("123456","")),200);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_security_grants WHERE user_id=12",Integer.class)).isZero();
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",12,new C2cMeController.Settings("2","QA Buyer",false,30,new C2cVerification("123456","")),409);
    }

    @Test void nicknameAndStrangerSettingsHaveVersionProtectionAndCannotTakeAnotherNickname() throws Exception {
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",12,new C2cMeController.Settings("1","QA Renamed",true,0,null),200);
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",13,new C2cMeController.Settings("1","QA Renamed",true,0,null),409);
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/me",13,null,200).get("nickname")).isEqualTo("QA Other");
        verifyNoInteractions(fixture.security);
    }

    @Test void quickRepliesAreIdempotentForTheirOwnerAndCannotBeOverwrittenByAnotherUser() throws Exception {
        UUID id=UUID.randomUUID();var request=new C2cMeController.QuickReply(id,"Synthetic quick reply");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/quick-replies",12,request,200);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/quick-replies",12,request,200);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/quick-replies",13,new C2cMeController.QuickReply(id,"Unauthorized change"),403);
        assertThat((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/me/quick-replies",13,null,200).get("items")).isEmpty();
        fixture.call(HttpMethod.DELETE,"/api/v1/c2c/me/quick-replies/"+id,13,null,200);
        assertThat((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/me/quick-replies",12,null,200).get("items")).hasSize(1);
    }

    @Test void followAndBlockArePrivateAndRejectSelfOrInternalEscrowAccounts() throws Exception {
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me/relations/FOLLOW/11",12,null,200);
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me/relations/BLOCK/11",12,null,200);
        var followed=fixture.call(HttpMethod.GET,"/api/v1/c2c/me/relations?kind=FOLLOW",12,null,200);
        assertThat((List<?>)followed.get("items")).hasSize(1);
        assertThat((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/me/relations?kind=FOLLOW",13,null,200).get("items")).isEmpty();
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me/relations/FOLLOW/12",12,null,400);
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me/relations/FOLLOW/1",12,null,400);
        fixture.call(HttpMethod.DELETE,"/api/v1/c2c/me/relations/BLOCK/11",12,null,200);
        assertThat((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/me/relations?kind=BLOCK",12,null,200).get("items")).isEmpty();
    }

    @Test void reportsVerifyOrderPartiesAndAnIdenticalReportDoesNotDuplicate() throws Exception {
        UUID ad=fixture.publish("SELL"),quote=fixture.quote(ad,12,"2");
        UUID order=UUID.fromString((String)fixture.create(quote,12,"qa-report-order-"+UUID.randomUUID(),"",200).get("resultId"));
        UUID id=UUID.randomUUID();var report=new C2cMeController.Report(id,"11",order,"Synthetic dispute report");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/reports",12,report,200);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/reports",12,report,200);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/reports",13,new C2cMeController.Report(UUID.randomUUID(),"11",order,"Unauthorized order report"),403);
        assertThat((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/me/reports",12,null,200).get("items")).hasSize(1);
        assertThat((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/me/reports",13,null,200).get("items")).isEmpty();
    }

    @Test void merchantApplicationRequiresExistingVerifiedKycAndCannotUndoSuspension() throws Exception {
        var request=new C2cMeController.MerchantApplication("0","Synthetic merchant application with sufficient details");
        var applied=fixture.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-application",12,request,200);
        assertThat(applied.get("status")).isEqualTo("PENDING");
        fixture.db.jdbc.update("UPDATE c2c_merchants SET status='SUSPENDED' WHERE user_id=12");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-application",12,new C2cMeController.MerchantApplication("1",request.application()),409);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-application",9,request,409);
    }

    @Test void unsettledIntentAmountsStayUnknownAndConfirmedEscrowTracksReleaseAndAdRefund() throws Exception {
        var publish=fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest(
                "qa-escrow-"+UUID.randomUUID(),fixture.payload("SELL"),new C2cVerification("123456","")),200);
        var pending=escrow();assertThat(pending.get("confirmedEscrowUnits")).isNull();
        assertThat(pending.get("pendingIntentCount")).isEqualTo("1");
        fixture.settle();assertThat(escrow().get("confirmedEscrowUnits")).isEqualTo("1000000000");
        UUID ad=UUID.fromString((String)publish.get("resultId")),quote=fixture.quote(ad,12,"2");
        UUID order=UUID.fromString((String)fixture.create(quote,12,"qa-escrow-order-"+UUID.randomUUID(),"",200).get("resultId"));
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/paid",12,new C2cController.PaidRequest(null),200);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/release",11,new C2cVerification("123456",""),200);
        assertThat(escrow().get("confirmedEscrowUnits")).isNull();
        fixture.settle();assertThat(escrow().get("confirmedEscrowUnits")).isEqualTo("800000000");
        String revision=Long.toString(fixture.bean(C2cAdsRepository.class).get(ad,false).revision());
        fixture.call(HttpMethod.POST,"/api/v1/c2c/ads/"+ad+"/status",11,new C2cController.AdAction(revision,"CLOSE",new C2cVerification("123456","")),200);
        fixture.settle();assertThat(escrow().get("confirmedEscrowUnits")).isEqualTo("0");
    }

    @Test void priceAlertsNotifyOncePerArmingAndReadMarkersRemainOwnerScoped() throws Exception {
        UUID ad=fixture.publish("SELL");
        var saved=fixture.call(HttpMethod.POST,"/api/v1/c2c/me/price-alerts",12,
                new C2cMeController.Alert(null,"0",fixture.marketId,"BUY","6.65",true),200);
        UUID id=UUID.fromString((String)saved.get("alertId"));var jobs=fixture.bean(C2cNotificationService.class);
        assertThat(jobs.processBatch()).isEqualTo(1);
        assertThat(jobs.processBatch()).isZero();
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/price-alerts",12,new C2cMeController.Alert(id,"1",fixture.marketId,"BUY","6.60",true),409);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/price-alerts",12,new C2cMeController.Alert(id,"2",fixture.marketId,"BUY","6.60",true),200);
        assertThat(jobs.processBatch()).isZero();
        var row=fixture.bean(C2cAdsRepository.class).get(ad,false);
        fixture.call(HttpMethod.PATCH,"/api/v1/c2c/ads/"+ad,11,new C2cController.AdEdit(Long.toString(row.revision()),"6.50","1","10000","Synthetic terms",0),200);
        assertThat(jobs.processBatch()).isEqualTo(1);
        var notifications=fixture.call(HttpMethod.GET,"/api/v1/c2c/me/notifications",12,null,200);
        assertThat(notifications.get("unreadCount")).isEqualTo("2");
        String through=(String)((Map<?,?>)((List<?>)notifications.get("items")).getFirst()).get("notificationId");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/notifications/read",13,new C2cMeController.ReadNotifications(through),200);
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/me/notifications",12,null,200).get("unreadCount")).isEqualTo("2");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/notifications/read",12,new C2cMeController.ReadNotifications(through),200);
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/me/notifications",12,null,200).get("unreadCount")).isEqualTo("0");
    }

    @Test void alertsRespectBlockedOrExpiredMerchantsAndClosedMarket() throws Exception {
        fixture.publish("SELL");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/price-alerts",12,new C2cMeController.Alert(null,"0",fixture.marketId,"BUY","7",true),200);
        var jobs=fixture.bean(C2cNotificationService.class);
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me/relations/BLOCK/11",12,null,200);
        assertThat(jobs.processBatch()).isZero();
        fixture.call(HttpMethod.DELETE,"/api/v1/c2c/me/relations/BLOCK/11",12,null,200);
        fixture.db.jdbc.update("UPDATE gateway_user_kyc_profiles SET expires_at=now()-interval '1 second' WHERE user_id=11");
        assertThat(jobs.processBatch()).isZero();
        fixture.db.jdbc.update("UPDATE gateway_user_kyc_profiles SET expires_at=NULL WHERE user_id=11");
        fixture.db.jdbc.update("UPDATE c2c_configuration SET enabled=false");
        assertThat(jobs.processBatch()).isZero();
        fixture.db.jdbc.update("UPDATE c2c_configuration SET enabled=true");
        assertThat(jobs.processBatch()).isEqualTo(1);
    }

    private Map<?,?> escrow() throws Exception {
        return (Map<?,?>)((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/me",11,null,200).get("escrow")).getFirst();
    }
}
