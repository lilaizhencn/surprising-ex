package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cMeController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.gateway.c2c.service.C2cNotificationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class C2cNotificationsPostgresTest {
    private C2cHttpFixture fixture;
    private C2cNotificationService notifications;

    @BeforeEach void setup() throws Exception {
        fixture = new C2cHttpFixture();
        notifications = fixture.bean(C2cNotificationService.class);
    }
    @AfterEach void cleanup() { if (fixture != null) fixture.close(); }

    @Test void aPendingBondCannotAppearOrFireAPriceAlert() throws Exception {
        fixture.publish("SELL");
        UUID alert = alert();
        fixture.db.jdbc.update("UPDATE c2c_merchant_bonds SET status='RESERVING' WHERE user_id=11");

        assertThat(notifications.processBatch()).isZero();
        assertOffers(0);
        assertArmed(alert);

        fixture.db.jdbc.update("UPDATE c2c_merchant_bonds SET status='LOCKED' WHERE user_id=11");
        assertOffers(1);
        assertThat(notifications.processBatch()).isEqualTo(1);
        assertThat(notifications.processBatch()).isZero();
    }

    @Test void aLockedBondMustCoverTheCurrentPolicyForBothReaders() throws Exception {
        fixture.publish("SELL");
        UUID alert = alert();
        fixture.db.jdbc.update("UPDATE c2c_merchant_bond_policy SET amount_units=200000000");

        assertThat(notifications.processBatch()).isZero();
        assertOffers(0);
        assertArmed(alert);

        fixture.db.jdbc.update("UPDATE c2c_merchant_bond_policy SET amount_units=100000000");
        assertOffers(1);
        assertThat(notifications.processBatch()).isEqualTo(1);
        var page = fixture.call(HttpMethod.GET, "/api/v1/c2c/me/notifications", 12, null, 200);
        var row = (Map<?, ?>) ((List<?>) page.get("items")).getFirst();
        var details = (Map<?, ?>) row.get("details");
        assertThat(details.get("marketId")).isEqualTo(fixture.marketId.toString());
        assertThat(details.get("targetPrice")).isInstanceOf(String.class);
        assertThat(new BigDecimal((String) details.get("targetPrice"))).isEqualByComparingTo("7");
    }

    @Test void viewerBlocksWorkInBothDirectionsAndOldBondsDoNotDuplicateAds() throws Exception {
        UUID ad = fixture.publish("SELL");
        alert();
        fixture.db.jdbc.update("""
                INSERT INTO c2c_merchant_bonds(bond_id,user_id,request_id,asset,amount_units,status)
                VALUES(?,11,?,'USDT',100000000,'RETURNED')
                """, UUID.randomUUID(), UUID.randomUUID());
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_eligible_ads WHERE ad_id=?", Integer.class, ad)).isEqualTo(1);
        for (long owner : List.of(11L, 12L)) {
            long target = owner == 11 ? 12 : 11;
            fixture.db.jdbc.update("INSERT INTO c2c_relations(user_id,target_user_id,kind) VALUES(?,?,'BLOCK')", owner, target);
            assertOffers(0);
            assertThat(notifications.processBatch()).isZero();
            fixture.db.jdbc.update("DELETE FROM c2c_relations WHERE user_id=? AND target_user_id=?", owner, target);
        }
        assertOffers(1);
        assertThat(notifications.processBatch()).isEqualTo(1);
    }

    @Test void failedNotificationInsertRollsBackTheArmingRevisionAndCanRetry() throws Exception {
        fixture.publish("SELL");
        UUID alert = alert();
        fixture.db.jdbc.execute("ALTER TABLE c2c_notifications ADD CONSTRAINT reject_price_alert CHECK(kind<>'PRICE_ALERT')");

        assertThatThrownBy(notifications::processBatch).isInstanceOf(DataIntegrityViolationException.class);

        assertArmed(alert);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_notifications", Integer.class)).isZero();
        fixture.db.jdbc.execute("ALTER TABLE c2c_notifications DROP CONSTRAINT reject_price_alert");
        assertThat(notifications.processBatch()).isEqualTo(1);
        assertThat(notifications.processBatch()).isZero();
        assertThat(fixture.db.jdbc.queryForObject("SELECT revision FROM c2c_price_alerts WHERE alert_id=?", Long.class, alert)).isEqualTo(2L);
    }

    @Test void cleanupOnlyDeletesExpiredGrantsAndOldUnreferencedQuotes() throws Exception {
        UUID ad = fixture.publish("SELL");
        UUID used = fixture.quote(ad, 12, "1");
        fixture.create(used, 12, "qa-cleanup-order-" + UUID.randomUUID(), "", 200);
        UUID unused = fixture.quote(ad, 12, "1");
        fixture.db.jdbc.update("UPDATE c2c_quotes SET expires_at=now()-interval '2 days' WHERE quote_id IN (?,?)", used, unused);
        fixture.db.jdbc.update("INSERT INTO c2c_security_grants(user_id,token_hash,expires_at) VALUES(12,?,now()-interval '1 hour'),(12,?,now()+interval '1 hour')",
                "a".repeat(64), "b".repeat(64));
        clearInvocations(fixture.core);

        assertThat(notifications.processBatch()).isZero();

        verifyNoInteractions(fixture.core);
        assertThat(fixture.db.jdbc.queryForList("SELECT quote_id FROM c2c_quotes", UUID.class)).containsExactly(used);
        assertThat(fixture.db.jdbc.queryForList("SELECT token_hash FROM c2c_security_grants", String.class)).containsExactly("b".repeat(64));
    }

    @Test @Timeout(15)
    void aPeerLockedAlertIsSkippedAndCanFireOnceAfterRelease() throws Exception {
        fixture.publish("SELL");
        UUID alert = alert();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var holder = threads.submit(() -> fixture.db.transaction(() -> {
                fixture.db.jdbc.queryForObject("SELECT alert_id FROM c2c_price_alerts WHERE alert_id=? FOR UPDATE", UUID.class, alert);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test lock release timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
                }
                return null;
            }));
            try {
                assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(threads.submit(notifications::processBatch).get(3, TimeUnit.SECONDS)).isZero();
            } finally { release.countDown(); }
            holder.get(3, TimeUnit.SECONDS);
        }
        assertThat(notifications.processBatch()).isEqualTo(1);
        assertThat(notifications.processBatch()).isZero();
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_notifications", Integer.class)).isEqualTo(1);
    }

    private UUID alert() throws Exception {
        return UUID.fromString((String) fixture.call(HttpMethod.POST, "/api/v1/c2c/me/price-alerts", 12,
                new C2cMeController.Alert(null, "0", fixture.marketId, "BUY", "7", true), 200).get("alertId"));
    }

    private void assertOffers(int count) throws Exception {
        var result = fixture.call(HttpMethod.GET, "/api/v1/c2c/ads?marketId=" + fixture.marketId, 12, null, 200);
        assertThat((List<?>) result.get("items")).hasSize(count);
    }

    private void assertArmed(UUID alert) {
        assertThat(fixture.db.jdbc.queryForMap("SELECT enabled,revision,triggered_at FROM c2c_price_alerts WHERE alert_id=?", alert))
                .containsEntry("enabled", true).containsEntry("revision", 1L).containsEntry("triggered_at", null);
    }
}
