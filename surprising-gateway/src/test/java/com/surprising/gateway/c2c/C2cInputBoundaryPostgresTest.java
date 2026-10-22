package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.model.C2cAdminReviewModels;
import com.surprising.gateway.c2c.model.C2cPaymentAccountInput.Details;
import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.service.C2cPaymentAccounts;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.HttpMethod;

/** Actual controller binding, approval/audit transactions, method policy and encrypted PostgreSQL data. */
@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cInputBoundaryPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception { fixture=new C2cHttpFixture(); }
    @AfterEach void cleanup() { if(fixture!=null)fixture.close(); }
    static Stream<Arguments> versions() {
        return Stream.of("merchant","payment","ad").flatMap(kind -> Stream.of("0","01","+1","9223372036854775808","1.0").map(version -> Arguments.of(kind,version)));
    }
    @ParameterizedTest @MethodSource("versions")
    void everyReviewVersionIsBoundBeforeBusinessMutation(String kind,String revision) throws Exception {
        String path;Object body;
        switch(kind) {
            case "merchant" -> { path="/api/v1/admin/c2c/merchants/11/review";body=new C2cAdminReviewModels.MerchantRequest(revision,"SUSPEND","VERIFIED","Synthetic format boundary test"); }
            case "payment" -> { path="/api/v1/admin/c2c/payment-accounts/"+fixture.makerAccount+"/review";body=new C2cAdminReviewModels.PaymentRequest(revision,"DISABLE",true,"Synthetic format boundary test"); }
            default -> { path="/api/v1/admin/c2c/ads/"+UUID.randomUUID()+"/status";body=new C2cAdminReviewModels.AdRequest(revision,"PAUSE","Synthetic format boundary test"); }
        }
        fixture.call(HttpMethod.POST,path,9,body,400);
        assertThat(fixture.bean(C2cPaymentAccounts.class).get(11,fixture.makerAccount,false).revision()).isEqualTo(1);
        verifyNoInteractions(fixture.core);
    }
    @ParameterizedTest @ValueSource(strings={"merchant","payment"})
    void exactLongMaxVersionReachesTheBusinessConflictCheck(String kind) throws Exception {
        if(kind.equals("merchant")) fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/merchants/11/review",9,
                new C2cAdminReviewModels.MerchantRequest(Long.toString(Long.MAX_VALUE),"SUSPEND","VERIFIED","Synthetic exact long version"),409);
        else fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/payment-accounts/"+fixture.makerAccount+"/review",9,
                new C2cAdminReviewModels.PaymentRequest(Long.toString(Long.MAX_VALUE),"DISABLE",true,"Synthetic exact long version"),409);
        verifyNoInteractions(fixture.core);
    }
    @ParameterizedTest @ValueSource(strings={"1234567","11111111111111111111111111111111111111111","qa-account","1234 5678","１２３４５６７８","+12345678"})
    void bankMethodRejectsIncompleteCardFacts(String account) throws Exception {
        fixture.call(HttpMethod.POST,"/api/v1/c2c/payment-accounts",13,payment("BANK_TRANSFER",account,"QA Bank"),400);
        verifyNoInteractions(fixture.core);
    }
    @Test void bankNameIsRequiredByTheCurrentMethodPolicy() throws Exception {
        fixture.call(HttpMethod.POST,"/api/v1/c2c/payment-accounts",13,payment("BANK_TRANSFER","12345678"," "),400);
    }
    @Test void validBankDetailsKeepLeadingZerosAndEncryptedOwnerBoundData() throws Exception {
        var result=fixture.call(HttpMethod.POST,"/api/v1/c2c/payment-accounts",13,payment("BANK_TRANSFER","000012345678","QA Bank"),200);
        UUID id=UUID.fromString((String)result.get("accountId"));
        var accounts=fixture.bean(C2cPaymentAccounts.class);var record=accounts.get(13,id,false);
        assertThat(record.maskedLabel()).isEqualTo("****5678");
        assertThat(record.ciphertext()).doesNotContain("000012345678");
        assertThat(accounts.details(record).accountNumber()).isEqualTo("000012345678");
        assertThatThrownBy(()->accounts.get(12,id,false)).hasMessage("收款账号不存在");
        verifyNoInteractions(fixture.core);
    }
    @Test void clientCannotSupplyTheComputedBankFact() throws Exception {
        var body=Map.of("revision","0","fiat","CNY","methodCode","BANK_TRANSFER",
                "details",Map.of("holderName","QA Account","accountNumber","qa-account","bankName","QA Bank","bankDetailsComplete",true),
                "verification",new C2cVerification("123456",""));
        fixture.call(HttpMethod.POST,"/api/v1/c2c/payment-accounts",13,body,400);
        verifyNoInteractions(fixture.core);
    }
    @Test void reviewRechecksStoredFactsAgainstAChangedMethodPolicy() throws Exception {
        var result=fixture.call(HttpMethod.POST,"/api/v1/c2c/payment-accounts",13,payment("ALIPAY","qa-account","QA Bank"),200);
        UUID id=UUID.fromString((String)result.get("accountId"));var accounts=fixture.bean(C2cPaymentAccounts.class);var before=accounts.get(13,id,false);
        fixture.db.jdbc.update("UPDATE c2c_payment_methods SET requires_bank_name=TRUE WHERE code='ALIPAY'");
        fixture.call(HttpMethod.POST,"/api/v1/admin/c2c/payment-accounts/"+id+"/review",9,
                new C2cAdminReviewModels.PaymentRequest(Long.toString(before.revision()),"APPROVE",true,"Synthetic changed method policy"),400);
        assertThat(accounts.get(13,id,false)).isEqualTo(before);verifyNoInteractions(fixture.core);
    }
    private C2cController.PaymentSave payment(String method,String number,String bank) {
        return new C2cController.PaymentSave(null,"0","CNY",method,new Details("QA Account",number,bank,null),new C2cVerification("123456",""));
    }
}
