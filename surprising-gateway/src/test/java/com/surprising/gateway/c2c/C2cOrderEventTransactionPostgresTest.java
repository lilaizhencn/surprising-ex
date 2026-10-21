package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.gateway.c2c.model.C2cOrderStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class C2cOrderEventTransactionPostgresTest {
    private C2cHttpFixture fixture;

    @BeforeEach void setup() throws Exception { fixture = new C2cHttpFixture(); }
    @AfterEach void cleanup() { if (fixture != null) fixture.close(); }

    @Test void failedCreationEventRollsBackOrderAndStockAndTheOriginalRequestCanResume() throws Exception {
        UUID adId = fixture.publish("SELL");
        UUID quoteId = fixture.quote(adId, 12, "2");
        String request = "qa-event-create-" + UUID.randomUUID();
        rejectEvent("ORDER_CREATED");

        fixture.create(quoteId, 12, request, "", 409);

        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders", Integer.class)).isZero();
        var ad = fixture.bean(C2cAdsRepository.class).get(adId, false);
        assertThat(ad.availableUnits()).isEqualTo(1_000_000_000L);
        assertThat(ad.reservedUnits()).isZero();
        assertThat(fixture.bean(C2cSubmissions.class).get(12, request, false).status()).isEqualTo("PROCESSING");

        allowEvents();
        var accepted = fixture.create(quoteId, 12, request, "", 200);
        var replay = fixture.create(quoteId, 12, request, "", 200);
        assertThat(replay.get("resultId")).isEqualTo(accepted.get("resultId"));
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_orders", Integer.class)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_order_events WHERE event_type='ORDER_CREATED'", Integer.class)).isEqualTo(1);
    }

    @Test void failedPaidEventKeepsTheOriginalPaymentWindowAndState() throws Exception {
        UUID orderId = order();
        var before = fixture.bean(C2cOrdersRepository.class).get(orderId, false);
        rejectEvent("BUYER_MARKED_PAID");

        fixture.call(HttpMethod.POST, "/api/v1/c2c/orders/" + orderId + "/paid", 12, new C2cController.PaidRequest(null), 409);

        assertThat(fixture.bean(C2cOrdersRepository.class).get(orderId, false)).isEqualTo(before);
        allowEvents();
        fixture.call(HttpMethod.POST, "/api/v1/c2c/orders/" + orderId + "/paid", 12, new C2cController.PaidRequest(null), 200);
        assertThat(fixture.bean(C2cOrdersRepository.class).get(orderId, false).status()).isEqualTo(C2cOrderStatus.PAID);
    }

    @Test void failedReleaseEventRollsBackNetAndFeeIntentsWithoutSendingCoreCommands() throws Exception {
        UUID orderId = paidOrder();
        rejectEvent("SELLER_CONFIRMED_RECEIPT");
        clearInvocations(fixture.core);

        fixture.call(HttpMethod.POST, "/api/v1/c2c/orders/" + orderId + "/release", 11, new C2cVerification("123456", ""), 409);

        assertThat(fixture.bean(C2cOrdersRepository.class).get(orderId, false).status()).isEqualTo(C2cOrderStatus.PAID);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE owner_type='ORDER'", Integer.class)).isZero();
        verifyNoInteractions(fixture.core);
        allowEvents();
        fixture.call(HttpMethod.POST, "/api/v1/c2c/orders/" + orderId + "/release", 11, new C2cVerification("123456", ""), 200);
        assertThat(fixture.db.jdbc.queryForObject("SELECT sum(amount_units) FROM c2c_fund_intents WHERE owner_type='ORDER'", Long.class))
                .isEqualTo(200_000_000L);
    }

    @Test void failedTerminalEventKeepsAppliedIntentsAndRetryDoesNotRepeatTransfers() throws Exception {
        UUID orderId = paidOrder();
        fixture.call(HttpMethod.POST, "/api/v1/c2c/orders/" + orderId + "/release", 11, new C2cVerification("123456", ""), 200);
        rejectEvent("COMPLETED");

        // Core确认先独立提交；直接验证业务事务失败，随后再交给正常调度入口重试。
        for (var intent : fixture.bean(C2cFundsRepository.class).due(64)) fixture.bean(C2cFunds.class).resume(intent.id());
        assertThatThrownBy(() -> fixture.bean(C2cOrders.class).finishFunding(orderId)).isInstanceOf(DataIntegrityViolationException.class);

        var order = fixture.bean(C2cOrdersRepository.class).get(orderId, false);
        assertThat(order.status()).isEqualTo(C2cOrderStatus.RELEASE_PENDING);
        var ad = fixture.bean(C2cAdsRepository.class).get(order.adId(), false);
        assertThat(ad.reservedUnits()).isEqualTo(200_000_000L);
        assertThat(ad.completedUnits()).isZero();
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE owner_type='ORDER' AND status='APPLIED'", Integer.class))
                .isEqualTo(2);

        allowEvents();
        clearInvocations(fixture.core);
        fixture.settle();
        verifyNoInteractions(fixture.core);
        assertThat(fixture.bean(C2cOrdersRepository.class).get(orderId, false).status()).isEqualTo(C2cOrderStatus.COMPLETED);
        assertThat(fixture.bean(C2cAdsRepository.class).get(order.adId(), false).completedUnits()).isEqualTo(200_000_000L);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_order_events WHERE order_id=? AND event_type='COMPLETED'", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test void quoteKeepsDatabaseClockLifetimeVersionsAndOwnership() throws Exception {
        UUID adId = fixture.publish("SELL");
        UUID quoteId = fixture.quote(adId, 12, "2");
        var quote = fixture.bean(C2cOrders.class).ownedQuote(12, quoteId);
        assertThat(quote.quantityUnits()).isEqualTo(200_000_000L);
        assertThat(quote.feeUnits()).isEqualTo(200_000L);
        assertThat(quote.adRevision()).isEqualTo(fixture.bean(C2cAdsRepository.class).get(adId, false).revision());
        assertThat(fixture.db.jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM expires_at-created_at) FROM c2c_quotes WHERE quote_id=?", BigDecimal.class, quoteId))
                .isEqualByComparingTo("60");
        fixture.call(HttpMethod.GET, "/api/v1/c2c/quotes/" + quoteId, 13, null, 403);
        assertThat(fixture.call(HttpMethod.GET, "/api/v1/c2c/quotes/" + quoteId, 12, null, 200).get("ownerUserId")).isEqualTo("12");
    }

    private UUID order() throws Exception {
        UUID ad = fixture.publish("SELL");
        return UUID.fromString((String) fixture.create(fixture.quote(ad, 12, "2"), 12,
                "qa-event-order-" + UUID.randomUUID(), "", 200).get("resultId"));
    }

    private UUID paidOrder() throws Exception {
        UUID order = order();
        fixture.call(HttpMethod.POST, "/api/v1/c2c/orders/" + order + "/paid", 12, new C2cController.PaidRequest(null), 200);
        return order;
    }

    private void rejectEvent(String type) {
        // Only fixed test event names reach this schema-owned constraint.
        fixture.db.jdbc.execute("ALTER TABLE c2c_order_events ADD CONSTRAINT reject_test_event CHECK(event_type<>'" + type + "')");
    }

    private void allowEvents() { fixture.db.jdbc.execute("ALTER TABLE c2c_order_events DROP CONSTRAINT reject_test_event"); }
}
