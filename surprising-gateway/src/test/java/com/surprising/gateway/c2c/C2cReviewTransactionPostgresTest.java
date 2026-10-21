package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.service.C2cAdminReview;
import com.surprising.gateway.c2c.service.C2cPaymentAccounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class C2cReviewTransactionPostgresTest {
    private C2cHttpFixture fixture;

    @BeforeEach void setup() throws Exception { fixture = new C2cHttpFixture(); }
    @AfterEach void cleanup() { if (fixture != null) fixture.close(); }

    @Test void merchantSuspensionAndAdPauseRollBackTogetherWhenRequiredAuditFails() throws Exception {
        UUID adId = fixture.publish("SELL");
        var adBefore = fixture.bean(C2cAdsRepository.class).get(adId, false);
        fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");

        fixture.call(HttpMethod.POST, "/api/v1/admin/c2c/merchants/11/review", 9,
                new C2cAdminReview.MerchantDecision("1", "SUSPEND", "VERIFIED", "Synthetic audit failure test"), 503);

        var adAfter = fixture.bean(C2cAdsRepository.class).get(adId, false);
        assertThat(adAfter.status()).isEqualTo(adBefore.status());
        assertThat(adAfter.revision()).isEqualTo(adBefore.revision());
        assertThat(fixture.db.jdbc.queryForMap("SELECT status,revision,reviewed_by FROM c2c_merchants WHERE user_id=11"))
                .containsEntry("status", "APPROVED").containsEntry("revision", 1L).containsEntry("reviewed_by", null);
    }

    @Test void stalePaymentRevisionRollsBackTheEarlierAdPauseAndKeepsOriginalDetails() throws Exception {
        UUID adId = fixture.publish("SELL");
        var adBefore = fixture.bean(C2cAdsRepository.class).get(adId, false);
        var payments = fixture.bean(C2cPaymentAccounts.class);
        var accountBefore = payments.get(11, fixture.makerAccount, false);

        fixture.call(HttpMethod.POST, "/api/v1/c2c/payment-accounts", 11,
                new C2cController.PaymentSave(fixture.makerAccount, Long.toString(accountBefore.revision() + 1),
                        "CNY", "ALIPAY", new C2cPaymentAccounts.Details("QA Updated", "qa-rejected-account", "", null),
                        new C2cVerification("123456", "")), 409);

        var adAfter = fixture.bean(C2cAdsRepository.class).get(adId, false);
        assertThat(adAfter.status()).isEqualTo(adBefore.status());
        assertThat(adAfter.revision()).isEqualTo(adBefore.revision());
        assertThat(payments.get(11, fixture.makerAccount, false)).isEqualTo(accountBefore);
    }

    @Test void paymentApprovalRollsBackWhenRequiredAuditFails() throws Exception {
        var payments = fixture.bean(C2cPaymentAccounts.class);
        var pending = payments.save(13, null, 0, "CNY", "ALIPAY",
                new C2cPaymentAccounts.Details("QA Pending", "qa-pending-account", "", null));
        fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");

        fixture.call(HttpMethod.POST, "/api/v1/admin/c2c/payment-accounts/" + pending.id() + "/review", 9,
                new C2cAdminReview.PaymentDecision("1", "APPROVE", true, "Synthetic audit failure test"), 503);

        assertThat(payments.get(13, pending.id(), false)).isEqualTo(pending);
    }

    @Test void reportDecisionRollsBackWhenRequiredAuditFails() throws Exception {
        UUID reportId = UUID.randomUUID();
        fixture.db.jdbc.update("INSERT INTO c2c_reports(report_id,user_id,target_user_id,reason) VALUES(?,12,11,?)",
                reportId, "Synthetic report transaction test");
        fixture.db.jdbc.execute("DROP TABLE gateway_admin_operation_logs");

        fixture.call(HttpMethod.POST, "/api/v1/admin/c2c/reports/" + reportId + "/review", 9,
                new C2cAdminReview.ReportDecision("REVIEWED", "Synthetic audit failure test"), 503);

        assertThat(fixture.db.jdbc.queryForMap("SELECT status,review_reason,reviewed_by FROM c2c_reports WHERE report_id=?", reportId))
                .containsEntry("status", "OPEN").containsEntry("review_reason", null).containsEntry("reviewed_by", null);
    }
}
