package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.c2c.task.C2cMerchantBondTask;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cConfigurationTransactionPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {fixture=new C2cHttpFixture();}
    @AfterEach void cleanup() {if(fixture!=null) fixture.close();}

    @Test void missingAuditRollsBackMarketAndEarlierAdPause() throws Exception {
        UUID ad=fixture.publish("SELL");var before=fixture.bean(C2cAdsRepository.class).get(ad,false);
        var market=fixture.bean(C2cMarketService.class).market(fixture.marketId,false);
        fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/markets",9,new C2cAdminController.MarketInput(
                fixture.marketId,"1","USDT","CNY",2,true,"1","10000","0.1","100",2000,900,5,true,100,"Synthetic failed market audit"),503);
        assertThat(fixture.bean(C2cMarketService.class).market(fixture.marketId,false)).isEqualTo(market);
        assertThat(fixture.bean(C2cAdsRepository.class).get(ad,false)).isEqualTo(before);
    }

    @Test void missingAuditRollsBackMethodAccountReviewAndAdPause() throws Exception {
        UUID ad=fixture.publish("SELL");var before=fixture.bean(C2cAdsRepository.class).get(ad,false);
        var account=fixture.bean(C2cPaymentAccounts.class).get(11,fixture.makerAccount,false);
        fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/payment-methods",9,
                new C2cAdminController.MethodInput("ALIPAY","1","Changed method",true,List.of("CNY"),true,false,"Synthetic failed method audit"),503);
        assertThat(fixture.bean(C2cAdsRepository.class).get(ad,false)).isEqualTo(before);
        assertThat(fixture.bean(C2cPaymentAccounts.class).get(11,fixture.makerAccount,false)).isEqualTo(account);
        assertThat(fixture.db.jdbc.queryForObject("SELECT revision FROM c2c_payment_methods WHERE code='ALIPAY'",Long.class)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT requires_bank_name FROM c2c_payment_methods WHERE code='ALIPAY'",Boolean.class)).isFalse();
    }

    @Test void configurationWriteRequiresAuditInItsOwnTransaction() throws Exception {
        var before=fixture.bean(C2cMarketService.class).configuration();fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");
        fixture.call(HttpMethod.PUT,"/api/v1/admin/c2c/configuration",9,
                new C2cAdminController.ConfigurationInput("1",false,12,"Synthetic failed configuration audit"),503);
        assertThat(fixture.bean(C2cMarketService.class).configuration()).isEqualTo(before);
    }

    @Test void missingAuditRollsBackBondPolicyAmountAndRevision() throws Exception {
        fixture.db.jdbc.update("UPDATE c2c_merchant_bonds SET status='RETURNED' WHERE user_id=11");
        var before=fixture.bean(C2cMerchantBonds.class).policy();fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");
        fixture.call(HttpMethod.PUT,"/api/v1/admin/c2c/merchant-bond-policy",9,
                new C2cMerchantController.Policy("1","USDT","2","Synthetic failed policy audit"),503);
        assertThat(fixture.bean(C2cMerchantBonds.class).policy()).isEqualTo(before);
    }

    @Test void failedDepositIntentDoesNotLeaveAPendingBond() throws Exception {
        apply(12);
        fixture.db.jdbc.execute("ALTER TABLE c2c_fund_intents ADD CONSTRAINT reject_bond_intent CHECK(purpose<>'MERCHANT_BOND')");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bond",12,
                new C2cMerchantController.Deposit(UUID.randomUUID(),"1",new C2cVerification("123456","")),409);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_merchant_bonds WHERE user_id=12",Long.class)).isZero();
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Long.class)).isZero();verifyNoInteractions(fixture.core);
    }

    @Test void failedRefundStateDoesNotWithdrawMerchantOrPersistARefundIntent() throws Exception {
        UUID bond=fixture.db.jdbc.queryForObject("SELECT bond_id FROM c2c_merchant_bonds WHERE user_id=11",UUID.class);
        fixture.db.jdbc.execute("ALTER TABLE c2c_merchant_bonds ADD CONSTRAINT reject_refund_state CHECK(status<>'REFUND_PENDING')");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bonds/"+bond+"/withdraw",11,null,409);
        assertThat(fixture.db.jdbc.queryForObject("SELECT status FROM c2c_merchant_bonds WHERE bond_id=?",String.class,bond)).isEqualTo("LOCKED");
        assertThat(fixture.db.jdbc.queryForObject("SELECT status FROM c2c_merchants WHERE user_id=11",String.class)).isEqualTo("APPROVED");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE purpose='MERCHANT_BOND_REFUND'",Long.class)).isZero();verifyNoInteractions(fixture.core);
    }

    @Test void confirmationFailureRollsBackTheWholeBatchAndRetryDoesNotResendCore() throws Exception {
        readyBonds();
        fixture.db.jdbc.execute("""
                CREATE FUNCTION reject_second_bond() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.user_id=13 AND NEW.status='LOCKED' THEN
                    RAISE EXCEPTION 'Synthetic second confirmation failure' USING ERRCODE='23514';
                END IF; RETURN NEW; END $$
                """);
        fixture.db.jdbc.execute("CREATE TRIGGER reject_second_bond BEFORE UPDATE ON c2c_merchant_bonds FOR EACH ROW EXECUTE FUNCTION reject_second_bond()");
        var task=new C2cMerchantBondTask(fixture.bean(C2cMerchantBonds.class));
        assertThatThrownBy(task::confirm).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.db.jdbc.queryForList("SELECT status FROM c2c_merchant_bonds WHERE user_id IN(12,13)",String.class)).containsOnly("RESERVING");
        assertThat(fixture.db.jdbc.queryForList("SELECT status FROM c2c_fund_intents",String.class)).containsOnly("APPLIED");
        fixture.db.jdbc.execute("DROP TRIGGER reject_second_bond ON c2c_merchant_bonds");task.confirm();task.confirm();
        assertThat(fixture.db.jdbc.queryForList("SELECT status FROM c2c_merchant_bonds WHERE user_id IN(12,13)",String.class)).containsOnly("LOCKED");verifyNoInteractions(fixture.core);
    }

    @Test @Timeout(15)
    void confirmationSkipsALockedBondAndFinishesItAfterRelease() throws Exception {
        readyBonds();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var holder=threads.submit(() -> fixture.db.transaction(() -> {
                fixture.db.jdbc.queryForObject("SELECT bond_id FROM c2c_merchant_bonds WHERE user_id=12 FOR UPDATE",UUID.class);locked.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test lock release timed out");}
                catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}return null;
            }));
            try {
                assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue();
                threads.submit(() -> fixture.bean(C2cMerchantBonds.class).confirm()).get(3,TimeUnit.SECONDS);
                assertThat(fixture.db.jdbc.queryForObject("SELECT status FROM c2c_merchant_bonds WHERE user_id=13",String.class)).isEqualTo("LOCKED");
                assertThat(fixture.db.jdbc.queryForObject("SELECT status FROM c2c_merchant_bonds WHERE user_id=12",String.class)).isEqualTo("RESERVING");
            } finally {release.countDown();}
            holder.get(3,TimeUnit.SECONDS);
        }
        fixture.bean(C2cMerchantBonds.class).confirm();
        assertThat(fixture.db.jdbc.queryForObject("SELECT status FROM c2c_merchant_bonds WHERE user_id=12",String.class)).isEqualTo("LOCKED");verifyNoInteractions(fixture.core);
    }

    private void apply(long user) throws Exception {
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-application",user,new C2cMeController.MerchantApplication("0","Synthetic merchant for transaction test"),200);
    }
    private void readyBonds() throws Exception {
        for(long user:List.of(12L,13L)) {
            apply(user);fixture.call(HttpMethod.POST,"/api/v1/c2c/me/merchant-bond",user,
                    new C2cMerchantController.Deposit(UUID.randomUUID(),"1",new C2cVerification("123456","")),200);
        }
        for(var intent:fixture.bean(C2cFundsRepository.class).due(64)) fixture.bean(C2cFunds.class).resume(intent.id());
        fixture.db.jdbc.update("UPDATE c2c_merchant_bonds SET updated_at='2000-01-01'::timestamptz+user_id*interval '1 second' WHERE user_id IN(12,13)");
        clearInvocations(fixture.core);
    }
}
