package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.controller.C2cMeController;
import com.surprising.gateway.c2c.controller.C2cMerchantController;
import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.service.C2cAdminReview;
import com.surprising.gateway.c2c.service.C2cFunds;
import com.surprising.gateway.c2c.service.C2cMerchantBonds;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.service.ProductAccountAdjustment;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cMerchantBondPostgresTest {
    C2cHttpFixture f;
    @BeforeEach void start() throws Exception {f=new C2cHttpFixture();}
    @AfterEach void stop() {if(f!=null)f.close();}
    private void apply() throws Exception {
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-application",12,
                new C2cMeController.MerchantApplication("0","Synthetic buyer merchant application"),200);
    }
    private Map<String,Object> deposit(UUID request) throws Exception {
        return f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bond",12,
                new C2cMerchantController.Deposit(request,"1",new C2cVerification("123456","")),200);
    }
    private Map<?,?> bond(Map<String,Object> response) {return (Map<?,?>)response.get("bond");}
    private void settle() {
        var intents=f.bean(C2cFundsRepository.class);var funds=f.bean(C2cFunds.class);
        for(var i:intents.due(64)) funds.resume(i.id());
        f.bean(C2cMerchantBonds.class).confirm();
    }

    @Test void approvalRequiresCommittedBondAndDuplicateDepositUsesOnlyOneOriginalIntent() throws Exception {
        apply();
        f.call(HttpMethod.POST,"/api/v1/admin/c2c/merchants/12/review",9,
                new C2cAdminReview.MerchantDecision("1","APPROVE","VERIFIED","Check merchant without committed bond"),409);
        UUID request=UUID.randomUUID();var first=deposit(request);
        assertThat(bond(first).get("status")).isEqualTo("RESERVING");
        var unconfirmed=(Map<?,?>)((List<?>)f.call(HttpMethod.GET,"/api/v1/c2c/me",12,null,200).get("escrow")).getFirst();
        assertThat(unconfirmed.get("confirmedEscrowUnits")).isNull();
        assertThat(bond(deposit(request)).get("bondId")).isEqualTo(bond(first).get("bondId"));
        verifyNoInteractions(f.core);
        f.call(HttpMethod.POST,"/api/v1/admin/c2c/merchants/12/review",9,
                new C2cAdminReview.MerchantDecision("1","APPROVE","VERIFIED","Check pending bond confirmation"),409);
        settle();
        assertThat(bond(deposit(request)).get("status")).isEqualTo("LOCKED");
        var entitlement=(Map<?,?>)((List<?>)f.call(HttpMethod.GET,"/api/v1/c2c/me",12,null,200).get("escrow")).getFirst();
        assertThat(entitlement.get("confirmedEscrowUnits")).isEqualTo("100000000");
        f.call(HttpMethod.POST,"/api/v1/admin/c2c/merchants/12/review",9,
                new C2cAdminReview.MerchantDecision("1","APPROVE","VERIFIED","Check committed merchant bond"),200);
        assertThat(f.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE owner_type='MERCHANT'",Integer.class)).isEqualTo(1);
        verify(f.core,times(1)).transferOut(any(),any());
    }

    @Test void unknownDebitKeepsTheOriginalTransferAndCannotUnlockOrCreateAnotherDeposit() throws Exception {
        apply();UUID request=UUID.randomUUID();var first=deposit(request);
        when(f.core.transferOut(any(),any())).thenReturn(new ProductAccountAdjustment(ProductAccountAdjustment.Status.UNKNOWN,"RESULT_UNKNOWN"));
        settle();
        assertThat(bond(deposit(request)).get("status")).isEqualTo("RESERVING");
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bond",12,
                new C2cMerchantController.Deposit(UUID.randomUUID(),"1",new C2cVerification("123456","")),409);
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+bond(first).get("bondId")+"/withdraw",12,null,409);
        f.db.jdbc.update("UPDATE c2c_fund_intents SET retry_after=now() WHERE owner_type='MERCHANT'");
        when(f.core.transferOut(any(),any())).thenReturn(new ProductAccountAdjustment(ProductAccountAdjustment.Status.APPLIED,null));
        settle();
        assertThat(bond(deposit(request)).get("status")).isEqualTo("LOCKED");
        var commands=org.mockito.ArgumentCaptor.forClass(com.surprising.account.api.model.ProductTransferOperationRequest.class);
        verify(f.core,times(2)).transferOut(any(),commands.capture());
        assertThat(commands.getAllValues().get(0).transferId()).isEqualTo(commands.getAllValues().get(1).transferId());
    }

    @Test void withdrawalReturnsOnlyTheOriginalOwnerAmountAfterConfirmedSettlement() throws Exception {
        apply();var first=deposit(UUID.randomUUID());settle();String id=(String)bond(first).get("bondId");
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+id+"/withdraw",13,null,403);
        var pending=f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+id+"/withdraw",12,null,200);
        assertThat(bond(pending).get("status")).isEqualTo("REFUND_PENDING");
        assertThat(bond(f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+id+"/withdraw",12,null,200)).get("status")).isEqualTo("REFUND_PENDING");
        settle();
        assertThat(bond(f.call(HttpMethod.GET,"/api/v1/c2c/me/merchant-bond",12,null,200)).get("status")).isEqualTo("RETURNED");
        assertThat(f.db.jdbc.queryForObject("SELECT status FROM c2c_merchants WHERE user_id=12",String.class)).isEqualTo("WITHDRAWN");
        var entitlement=(Map<?,?>)((List<?>)f.call(HttpMethod.GET,"/api/v1/c2c/me",12,null,200).get("escrow")).getFirst();
        assertThat(entitlement.get("confirmedEscrowUnits")).isEqualTo("0");
        var refund=f.db.jdbc.queryForMap("SELECT * FROM c2c_fund_intents WHERE purpose='MERCHANT_BOND_REFUND'");
        assertThat(refund.get("target_user_id")).isEqualTo(12L);
        assertThat(refund.get("amount_units")).isEqualTo(100000000L);
        assertThat(refund.get("asset")).isEqualTo("USDT");
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+id+"/withdraw",12,null,200);
        verify(f.core,times(2)).transferOut(any(),any());
    }

    @Test void openAdsAndOrdersAndRiskSuspensionPreventDepositWithdrawal() throws Exception {
        UUID ad=f.publish("SELL");
        String id=(String)bond(f.call(HttpMethod.GET,"/api/v1/c2c/me/merchant-bond",11,null,200)).get("bondId");
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+id+"/withdraw",11,null,409);
        UUID quote=f.quote(ad,12,"1");f.create(quote,12,"bond-open-order-request-001","123456",200);f.settle();
        var row=f.bean(C2cAdsRepository.class).get(ad,false);
        f.call(HttpMethod.POST,"/api/v1/c2c/ads/"+ad+"/status",11,new C2cController.AdAction(Long.toString(row.revision()),"CLOSE",new C2cVerification("123456","")),200);
        f.settle();
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+id+"/withdraw",11,null,409);
        f.db.jdbc.update("UPDATE c2c_merchants SET status='SUSPENDED' WHERE user_id=11");
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+id+"/withdraw",11,null,409);
        assertThat(f.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE purpose='MERCHANT_BOND_REFUND'",Integer.class)).isZero();
    }

    @Test void policyNeedsPermissionAuditAndCannotChangeWhileBondsExist() throws Exception {
        var policy=new C2cMerchantController.Policy("1","USDT","2","Change policy while existing merchant bond is locked");
        f.call(HttpMethod.PUT,"/api/v1/admin/c2c/merchant-bond-policy",12,policy,403);
        f.call(HttpMethod.PUT,"/api/v1/admin/c2c/merchant-bond-policy",9,policy,409);
        assertThat(f.bean(C2cMerchantBonds.class).policy().get("amountUnits")).isEqualTo("100000000");
        assertThatThrownBy(()->f.db.jdbc.update("UPDATE c2c_merchant_bonds SET amount_units=1 WHERE user_id=11"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void insufficientBalanceAndInvalidSecurityNeverBecomeLockedBonds() throws Exception {
        apply();
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bond",12,
                new C2cMerchantController.Deposit(UUID.randomUUID(),"1",new C2cVerification("000000","")),409);
        assertThat(f.db.jdbc.queryForObject("SELECT count(*) FROM c2c_merchant_bonds WHERE user_id=12",Integer.class)).isZero();
        when(f.core.transferOut(any(),any())).thenReturn(new ProductAccountAdjustment(ProductAccountAdjustment.Status.REJECTED,"INSUFFICIENT_BALANCE"));
        UUID request=UUID.randomUUID();deposit(request);settle();
        assertThat(bond(deposit(request)).get("status")).isEqualTo("FAILED");
        f.call(HttpMethod.POST,"/api/v1/admin/c2c/merchants/12/review",9,
                new C2cAdminReview.MerchantDecision("1","APPROVE","VERIFIED","Rejected bond cannot qualify merchant"),409);
        verify(f.core,never()).transferIn(any(),any());
    }

    @Test void simultaneousDepositRequestsCannotDoubleChargeAndOldReceiptsStayOnTheirOwnCycle() throws Exception {
        apply();UUID original=UUID.randomUUID();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->deposit(original));var two=pool.submit(()->deposit(original));
            assertThat(bond(one.get(10,java.util.concurrent.TimeUnit.SECONDS)).get("bondId"))
                    .isEqualTo(bond(two.get(10,java.util.concurrent.TimeUnit.SECONDS)).get("bondId"));
        }
        settle();var first=bond(deposit(original));
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+first.get("bondId")+"/withdraw",12,null,200);settle();
        f.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-application",12,
                new C2cMeController.MerchantApplication("2","Apply again after completed original refund"),200);
        var next=bond(deposit(UUID.randomUUID()));
        assertThat(next.get("status")).isEqualTo("RESERVING");
        assertThat(bond(deposit(original)).get("status")).isEqualTo("RETURNED");
        assertThat(bond(f.call(HttpMethod.GET,"/api/v1/c2c/me/merchant-bond?requestId="+original,12,null,200)).get("bondId")).isEqualTo(first.get("bondId"));
        assertThat(f.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE purpose='MERCHANT_BOND'",Integer.class)).isEqualTo(2);
    }
}
