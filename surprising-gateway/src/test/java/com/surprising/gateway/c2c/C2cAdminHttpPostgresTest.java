package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.account.api.model.*;
import com.surprising.gateway.provider.auth.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cAdminHttpPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {fixture=new C2cHttpFixture();}
    @AfterEach void cleanup(){if(fixture!=null)fixture.close();}

    @Test void ordinaryUsersCannotUseAdminRoutesAndSupportDefaultsExcludeMoneyAndSensitiveAccess() throws Exception {
        fixture.call(HttpMethod.GET,"/api/v1/admin/c2c/configuration",12,null,403);
        fixture.call(HttpMethod.GET,"/api/v1/admin/c2c/payment-accounts/"+fixture.makerAccount,12,null,403);
        assertThat(fixture.db.jdbc.queryForObject("""
                SELECT count(*) FROM gateway_role_permissions rp JOIN gateway_roles r USING(role_id) JOIN gateway_permissions p USING(permission_id)
                WHERE r.role_code='SUPPORT' AND p.permission_code<>'admin.c2c.read'
                """,Integer.class)).isZero();
        assertThat(fixture.db.jdbc.queryForObject("""
                SELECT count(*) FROM gateway_role_permissions rp JOIN gateway_roles r USING(role_id) JOIN gateway_permissions p USING(permission_id)
                WHERE r.role_code='ADMIN' AND p.permission_code='admin.c2c.resolve'
                """,Integer.class)).isEqualTo(1);
    }

    @Test void appealAlwaysRequiresAnotherAdministratorEvenWhenGeneralApprovalIsDisabled() throws Exception {
        var dispute=dispute();String path=decisionPath(dispute.appeal());
        var rejected=fixture.call(HttpMethod.POST,path,9,new C2cAdminReview.AppealDecision("RELEASE","Synthetic checked payment evidence"),403);
        assertThat(rejected.get("code")).isEqualTo("C2C_APPROVAL_REQUIRED");
        assertThat(fixture.bean(C2cOrdersRepository.class).get(dispute.order(),false).status()).isEqualTo(C2cOrderStatus.APPEALED);
        assertThat(fixture.bean(C2cFundsRepository.class).find("ORDER",dispute.order(),"ORDER_RELEASE")).isNull();
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM gateway_admin_operation_logs WHERE service='c2c' AND NOT success",Integer.class)).isEqualTo(1);
    }

    @Test void bodyAndPathBoundApprovalCannotBeReusedForAnotherDecisionAndReleaseKeepsOriginalParties() throws Exception {
        var dispute=dispute();String path=decisionPath(dispute.appeal());
        var body=new C2cAdminReview.AppealDecision("RELEASE","Synthetic checked payment evidence");
        long approval=approve(path,body,9,10);
        approved(path,9,new C2cAdminReview.AppealDecision("REFUND",body.reason()),approval,403);
        assertThat(approvalStatus(approval)).isEqualTo("APPROVED");
        long wrongPath=approve(decisionPath(UUID.randomUUID()),body,9,10);
        approved(path,9,body,wrongPath,403);assertThat(approvalStatus(wrongPath)).isEqualTo("APPROVED");
        var accepted=approved(path,9,body,approval,200);
        assertThat(accepted.get("status")).isEqualTo("RELEASE_PENDING");
        assertThat(accepted).doesNotContainKey("payment");
        assertThat(approvalStatus(approval)).isEqualTo("CONSUMED");
        fixture.settle();
        var order=fixture.bean(C2cOrdersRepository.class).get(dispute.order(),false);
        assertThat(order.status()).isEqualTo(C2cOrderStatus.COMPLETED);
        assertThat(fixture.bean(C2cFundsRepository.class).find("ORDER",order.id(),"ORDER_RELEASE").targetUserId()).isEqualTo(12);
        approved(path,9,body,approval,403);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE owner_type='ORDER'",Integer.class)).isEqualTo(2);
        verify(fixture.auth).requireAdminPermission(10L,List.of(),"admin.c2c.resolve");
    }

    @Test void aCounterpartyCannotServeAsApproverEvenIfItAlsoHasAnAdminRole() throws Exception {
        var dispute=dispute();String path=decisionPath(dispute.appeal());
        var sellerAdmin=new AuthModels.JwtPrincipal(11,"qa_seller_admin","NORMAL",List.of("ADMIN"),Instant.now().plusSeconds(600));
        doReturn(sellerAdmin).when(fixture.auth).authenticateAdminBearer("Bearer qa-11");
        var body=new C2cAdminReview.AppealDecision("REFUND","Synthetic independently checked evidence");
        long approval=approve(path,body,9,11);
        approved(path,9,body,approval,403);
        assertThat(approvalStatus(approval)).isEqualTo("APPROVED");
        assertThat(fixture.bean(C2cOrdersRepository.class).get(dispute.order(),false).status()).isEqualTo(C2cOrderStatus.APPEALED);
    }

    @Test void selfApprovalAndRevokedApproverPermissionAreRejected() throws Exception {
        var dispute=dispute();String path=decisionPath(dispute.appeal());var body=new C2cAdminReview.AppealDecision("REFUND","Synthetic payment verification");
        var service=fixture.bean(AdminApprovalService.class);
        var request=new AuthModels.AdminApprovalCreateRequest("c2c","POST",path,null,C2cAdminAccess.hash(fixture.json.writeValueAsBytes(body)),"Synthetic independent approval reason");
        var pending=service.create("Bearer qa-9",request);
        assertThatThrownBy(()->service.approve("Bearer qa-9",pending.approvalId(),"Not independent")).isInstanceOf(IllegalStateException.class);
        long approval=approve(path,body,9,10);
        doThrow(new IllegalStateException("test revoked permission")).when(fixture.auth).requireAdminPermission(10L,List.of(),"admin.c2c.resolve");
        approved(path,9,body,approval,403);
        assertThat(approvalStatus(approval)).isEqualTo("APPROVED");
    }

    @Test void manualPaymentReviewRequiresAttestationAndSensitiveReadsRequireAnAuditWrite() throws Exception {
        var account=fixture.bean(C2cPaymentAccounts.class).save(11,null,0,"CNY","ALIPAY",new C2cPaymentAccounts.Details("QA Review Holder","qa-review-account","",null));
        var list=fixture.call(HttpMethod.GET,"/api/v1/admin/c2c/payment-accounts?status=PENDING",9,null,200);
        assertThat(fixture.json.writeValueAsString(list)).doesNotContain("qa-review-account","holderName","ciphertext");
        String path="/api/v1/admin/c2c/payment-accounts/"+account.id()+"/review";
        fixture.call(HttpMethod.POST,path,9,new C2cAdminReview.PaymentDecision("1","APPROVE",false,"Synthetic manual identity check"),400);
        assertThat(fixture.bean(C2cPaymentAccounts.class).get(11,account.id(),false).status()).isEqualTo("PENDING");
        fixture.call(HttpMethod.POST,path,9,new C2cAdminReview.PaymentDecision("1","APPROVE",true,"Synthetic manual identity check"),200);
        var details=fixture.call(HttpMethod.GET,"/api/v1/admin/c2c/payment-accounts/"+account.id(),9,null,200);
        assertThat(((Map<?,?>)details.get("details")).get("accountNumber")).isEqualTo("qa-review-account");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM gateway_admin_operation_logs WHERE success AND http_method='GET'",Integer.class)).isEqualTo(1);
        fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");
        var unavailable=fixture.call(HttpMethod.GET,"/api/v1/admin/c2c/payment-accounts/"+account.id(),9,null,503);
        assertThat(fixture.json.writeValueAsString(unavailable)).doesNotContain("qa-review-account","QA Review Holder");
    }

    @Test void merchantSuspensionPausesAdsAndForceCloseRequiresApprovedFixedRecipientRefund() throws Exception {
        UUID ad=fixture.publish("SELL");
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/merchants/11/review",9,
                new C2cAdminReview.MerchantDecision("1","SUSPEND","VERIFIED","Synthetic risk review suspension"),200);
        var row=fixture.bean(C2cAdsRepository.class).get(ad,false);assertThat(row.status()).isEqualTo("PAUSED");
        String path="/api/v1/admin/c2c/ads/"+ad+"/close";
        var command=new C2cAdminReview.AdDecision(Long.toString(row.revision()),"CLOSE","Synthetic independent closure reason");
        fixture.call(HttpMethod.POST,path,9,command,403);
        long approval=approve(path,command,9,10);
        var accepted=approved(path,9,command,approval,200);assertThat(accepted.get("status")).isEqualTo("CLOSING");
        var refund=fixture.bean(C2cFundsRepository.class).find("AD",ad,"AD_REFUND");
        assertThat(refund.targetUserId()).isEqualTo(11);assertThat(refund.amountUnits()).isEqualTo(1000000000);
        fixture.settle();assertThat(fixture.bean(C2cAdsRepository.class).get(ad,false).status()).isEqualTo("CLOSED");
    }

    @Test void changingMarketInvalidatesQuotesAndPausesAdsButExistingOrderKeepsItsOriginalFee() throws Exception {
        UUID ad=fixture.publish("SELL"),quote=fixture.quote(ad,12,"2");
        UUID order=UUID.fromString((String)fixture.create(quote,12,"qa-fee-"+UUID.randomUUID(),"",200).get("resultId"));
        UUID oldQuote=fixture.quote(ad,12,"1");
        var update=new C2cAdminMarkets.MarketInput(fixture.marketId,"1","USDT","CNY",2,true,"1","100000","1","100",2000,900,3,true,100,"Synthetic fee policy update");
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/markets",9,update,200);
        assertThat(fixture.bean(C2cAdsRepository.class).get(ad,false).status()).isEqualTo("PAUSED");
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/quotes/"+oldQuote,12,null,200).get("valid")).isEqualTo(false);
        fixture.create(oldQuote,12,"qa-old-fee-"+UUID.randomUUID(),"",409);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/paid",12,new C2cController.PaidRequest(null),200);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/release",11,new C2cVerification("123456",""),200);
        assertThat(fixture.bean(C2cFundsRepository.class).find("ORDER",order,"BUYER_FEE").amountUnits()).isEqualTo(200000);
        fixture.settle();assertThat(fixture.bean(C2cOrdersRepository.class).get(order,false).status()).isEqualTo(C2cOrderStatus.COMPLETED);
    }

    @Test void configurationCannotReopenUntilIntentsAndRealtimeBalancesReconcile() throws Exception {
        fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest("qa-pending-"+UUID.randomUUID(),fixture.payload("SELL"),new C2cVerification("123456","")),200);
        fixture.call(HttpMethod.PUT,"/api/v1/admin/c2c/configuration",9,new C2cAdminMarkets.ConfigurationInput("1",false,20,"Synthetic market closure"),200);
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/admin/c2c/reconciliation",9,null,200).get("status")).isEqualTo("PENDING");
        fixture.call(HttpMethod.PUT,"/api/v1/admin/c2c/configuration",9,new C2cAdminMarkets.ConfigurationInput("2",true,20,"Synthetic market reopening"),409);
        fixture.settle();
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/admin/c2c/reconciliation",9,null,200).get("status")).isEqualTo("MISMATCH");
        var configuration=fixture.bean(C2cMarkets.class).configuration();
        when(fixture.accountQueries.balances(configuration.escrowUserId())).thenReturn(new BalanceQueryResponse(1,List.of(
                new BalanceResponse(configuration.escrowUserId(),"USDT",1000000000,0,1000000000,Instant.now()))));
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/admin/c2c/reconciliation",9,null,200).get("status")).isEqualTo("MATCHED");
        fixture.call(HttpMethod.PUT,"/api/v1/admin/c2c/configuration",9,new C2cAdminMarkets.ConfigurationInput("2",true,20,"Synthetic market reopening"),200);
    }

    @Test void intentFactsAndSettlementProgressCannotBeMutatedBySqlUpdates() throws Exception {
        var created=fixture.call(HttpMethod.POST,"/api/v1/c2c/ads",11,new C2cController.PublishRequest("qa-immutable-"+UUID.randomUUID(),fixture.payload("SELL"),new C2cVerification("123456","")),200);
        UUID ad=UUID.fromString((String)created.get("resultId"));var intent=fixture.bean(C2cFundsRepository.class).find("AD",ad,"AD_RESERVE");
        assertThatThrownBy(()->fixture.db.jdbc.update("UPDATE c2c_fund_intents SET amount_units=amount_units+1 WHERE intent_id=?",intent.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->fixture.db.jdbc.update("UPDATE c2c_fund_intents SET status='APPLIED' WHERE intent_id=?",intent.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        fixture.settle();
        assertThatThrownBy(()->fixture.db.jdbc.update("UPDATE c2c_fund_intents SET status='PREPARED' WHERE intent_id=?",intent.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(fixture.bean(C2cFundsRepository.class).get(intent.id()).amountUnits()).isEqualTo(1000000000);
    }

    @Test void ordinaryAdminWritesRespectGlobalApprovalAndRollbackConsumptionOnBadVersion() throws Exception {
        fixture.properties.getSecurity().setRequireApprovalForHighRiskAdminWrites(true);
        String path="/api/v1/admin/c2c/payment-methods";
        var input=new C2cAdminMarkets.MethodInput("ALIPAY","1","Synthetic label",true,List.of("CNY"),false,true,"Synthetic payment method update");
        fixture.call(HttpMethod.POST,path,9,input,403);
        long valid=approve(path,input,9,10);approved(path,9,input,valid,200);
        assertThat(approvalStatus(valid)).isEqualTo("CONSUMED");
        var stale=new C2cAdminMarkets.MethodInput("ALIPAY","1","Stale label",true,List.of("CNY"),false,true,"Synthetic stale revision update");
        long invalid=approve(path,stale,9,10);approved(path,9,stale,invalid,409);
        assertThat(approvalStatus(invalid)).isEqualTo("APPROVED");
        assertThat(fixture.db.jdbc.queryForObject("SELECT label FROM c2c_payment_methods WHERE code='ALIPAY'",String.class)).isEqualTo("Synthetic label");
    }

    @Test void initializationUsesOnlyFixedInternalAccountsAndZeroAmountsOutsideDatabaseTransactions() throws Exception {
        fixture.db.jdbc.update("UPDATE c2c_configuration SET enabled=false");
        when(fixture.accountCommands.adjustBalance(any(),any(),any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            BalanceAdjustmentRequest command=call.getArgument(0);
            assertThat(command.amountUnits()).isZero();
            assertThat(command.asset()).isEqualTo("USDT");
            return null;
        });
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/initialization",12,null,403);
        verifyNoInteractions(fixture.accountCommands);
        var result=fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/initialization",9,null,200);
        assertThat(result.get("commandsApplied")).isEqualTo(true);
        assertThat(fixture.bean(C2cMarkets.class).configuration().enabled()).isFalse();
        var captured=org.mockito.ArgumentCaptor.forClass(BalanceAdjustmentRequest.class);
        verify(fixture.accountCommands,times(2)).adjustBalance(captured.capture(),eq("9"),any());
        var config=fixture.bean(C2cMarkets.class).configuration();
        assertThat(captured.getAllValues()).extracting(BalanceAdjustmentRequest::userId)
                .containsExactly(config.escrowUserId(),config.feeUserId());
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isZero();
    }

    @Test void unknownInitializationRetriesTheSameZeroCommandAndDoesNotPretendQueriesAreReady() throws Exception {
        when(fixture.accountCommands.adjustBalance(any(),any(),any())).thenThrow(new IllegalStateException("Synthetic lost reply"));
        var unknown=fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/initialization",9,null,200);
        assertThat(unknown.get("commandsApplied")).isEqualTo(false);
        doReturn(null).when(fixture.accountCommands).adjustBalance(any(),any(),any());
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/initialization",9,null,200);
        var captured=org.mockito.ArgumentCaptor.forClass(BalanceAdjustmentRequest.class);
        verify(fixture.accountCommands,times(4)).adjustBalance(captured.capture(),eq("9"),any());
        var commands=captured.getAllValues();
        assertThat(commands.get(0)).isEqualTo(commands.get(2));
        assertThat(commands.get(1)).isEqualTo(commands.get(3));
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/initialization",9,Map.of("amountUnits",100,"userId",13),400);
        verifyNoMoreInteractions(fixture.accountCommands);
    }

    @Test void initializationApprovalAndAuditMustCommitBeforeAnyCoreCommand() throws Exception {
        fixture.properties.getSecurity().setRequireApprovalForHighRiskAdminWrites(true);
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/initialization",9,null,403);
        verifyNoInteractions(fixture.accountCommands);
        fixture.properties.getSecurity().setRequireApprovalForHighRiskAdminWrites(false);
        fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/initialization",9,null,503);
        verifyNoInteractions(fixture.accountCommands);
    }

    private record Dispute(UUID order,UUID appeal) { }
    private Dispute dispute() throws Exception {
        UUID ad=fixture.publish("SELL"),quote=fixture.quote(ad,12,"2");
        UUID order=UUID.fromString((String)fixture.create(quote,12,"qa-appeal-"+UUID.randomUUID(),"",200).get("resultId"));
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/paid",12,new C2cController.PaidRequest(null),200);
        var appealed=fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/appeal",12,new C2cController.Reason("Synthetic disputed payment evidence"),200);
        return new Dispute(order,UUID.fromString((String)appealed.get("appealId")));
    }
    private String decisionPath(UUID id) {return "/api/v1/admin/c2c/appeals/"+id+"/decision";}
    private long approve(String path,Object body,long requester,long reviewer) {
        var service=fixture.bean(AdminApprovalService.class);
        var request=new AuthModels.AdminApprovalCreateRequest("c2c","POST",path,null,C2cAdminAccess.hash(fixture.json.writeValueAsBytes(body)),"Synthetic independent approval");
        var approval=service.create("Bearer qa-"+requester,request);
        service.approve("Bearer qa-"+reviewer,approval.approvalId(),"Synthetic evidence checked");return approval.approvalId();
    }
    private Map<String,Object> approved(String path,long user,Object body,long approval,int expected) throws Exception {
        var response=fixture.perform(HttpMethod.POST,path,user,body,Map.of("X-Admin-Approval-Id",Long.toString(approval))).getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(expected);
        return fixture.json.readValue(response.getContentAsString(),Map.class);
    }
    private String approvalStatus(long id) {
        return fixture.db.jdbc.queryForObject("SELECT status FROM gateway_admin_approval_requests WHERE approval_id=?",String.class,id);
    }
}
