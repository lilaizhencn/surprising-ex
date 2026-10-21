package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.c2c.repository.C2cAppealRepository;
import com.surprising.gateway.c2c.repository.C2cMarketRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentAccountRepository;
import com.surprising.gateway.c2c.repository.C2cReportRepository;
import com.surprising.gateway.c2c.service.C2cAdminQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class C2cAdminQueryPostgresTest {
    private C2cHttpFixture fixture;
    private CountingJdbc jdbc;
    private C2cAdminQueryService queries;

    @BeforeEach void setup() throws Exception {
        fixture = new C2cHttpFixture();
        jdbc = new CountingJdbc(fixture.db.jdbc.getDataSource());
        queries = new C2cAdminQueryService(new C2cMerchantRepository(jdbc), new C2cPaymentAccountRepository(jdbc),
                new C2cAdsRepository(jdbc), new C2cOrdersRepository(jdbc), new C2cFundsRepository(jdbc),
                new C2cAppealRepository(jdbc), new C2cReportRepository(jdbc), new C2cMarketRepository(jdbc));
    }

    @AfterEach void cleanup() { if (fixture != null) fixture.close(); }

    @Test void adAndOrderPagesUseAtMostTwoQueriesRegardlessOfPageSize() throws Exception {
        UUID ad = fixture.publish("SELL");
        fixture.publish("BUY");
        for (int i = 0; i < 2; i++) {
            UUID quote = fixture.quote(ad, 12, "1");
            fixture.create(quote, 12, "qa-page-" + UUID.randomUUID(), "", 200);
        }

        jdbc.selects = 0;
        var adPage = queries.ads(null, null, null, 10, 0);
        assertThat(jdbc.selects).isEqualTo(2);
        assertThat(adPage.items()).hasSize(2);
        assertThat(adPage.hasMore()).isFalse();
        assertThat(adPage.items()).allSatisfy(view -> {
            assertThat(view.get("makerUserId")).isEqualTo("11");
            assertThat(((Map<?, ?>) view.get("market")).get("assetScale")).isEqualTo("100000000");
        });

        jdbc.selects = 0;
        assertThat(queries.orders(null, 12L, null, null, 10, 0).items()).hasSize(2);
        assertThat(jdbc.selects).isEqualTo(2);

        jdbc.selects = 0;
        var orderPage = queries.orders(null, 12L, null, fixture.marketId, 1, 0);
        assertThat(jdbc.selects).isEqualTo(2);
        assertThat(orderPage.hasMore()).isTrue();
        assertThat(orderPage.items()).singleElement().satisfies(view -> {
            assertThat(view.get("quantityUnits")).isEqualTo("100000000");
            assertThat(view).doesNotContainKeys("payment_details_ciphertext", "paymentCiphertext", "payment");
        });
        String first = (String) orderPage.items().getFirst().get("orderId");
        assertThat(queries.orders(null, 12L, null, null, 1, 1).items())
                .singleElement().satisfies(view -> assertThat(view.get("orderId")).isNotEqualTo(first));

        jdbc.selects = 0;
        assertThat(queries.orders(null, 13L, null, null, 10, 0).items()).isEmpty();
        assertThat(jdbc.selects).isEqualTo(1);
    }

    @Test void allAdminReadProjectionsKeepStringIdentifiersAndExcludeSensitiveFields() throws Exception {
        UUID ad = fixture.publish("SELL");
        UUID quote = fixture.quote(ad, 12, "1");
        UUID order = UUID.fromString((String) fixture.create(quote, 12, "qa-safe-" + UUID.randomUUID(), "", 200).get("resultId"));
        fixture.call(HttpMethod.POST, "/api/v1/c2c/orders/" + order + "/appeal", 12,
                new C2cController.Reason("Synthetic query projection review"), 200);
        fixture.db.jdbc.update("INSERT INTO c2c_reports(report_id,user_id,target_user_id,reason) VALUES(?,12,11,?)",
                UUID.randomUUID(), "Synthetic report for query projection");

        var merchants = fixture.call(HttpMethod.GET, "/api/v1/admin/c2c/merchants?userId=11&limit=1", 9, null, 200);
        var merchant = (Map<?, ?>) ((List<?>) merchants.get("items")).getFirst();
        assertThat(merchant.get("userId")).isEqualTo("11");
        assertThat(merchant.get("revision")).isEqualTo("1");
        assertThat(merchant.get("bondAmount")).isEqualTo("1");
        assertThat(merchant.get("kycExpiresAt")).isNull();

        for (String path : List.of("payment-accounts", "ads", "orders", "fund-intents", "appeals", "reports")) {
            var page = fixture.call(HttpMethod.GET, "/api/v1/admin/c2c/" + path + "?limit=1", 9, null, 200);
            assertThat((List<?>) page.get("items")).hasSize(1);
            String body = fixture.json.writeValueAsString(page);
            assertThat(body).doesNotContain("qa-maker-account", "qa-taker-account", "holderName", "ciphertext", "documentNumber");
        }
        assertThat(queries.appeals("OPEN", order, 1, 0).items()).singleElement().satisfies(appeal -> {
            assertThat(appeal.orderId()).isEqualTo(order.toString());
            assertThat(appeal.openerUserId()).isEqualTo("12");
            assertThat(appeal.approvalId()).isNull();
        });
        assertThat(queries.reports(null, 13L, 1, 0).items()).isEmpty();
    }

    /** 所有当前页Repository都使用RowMapper查询，计数实际交给JdbcTemplate执行的SELECT。 */
    private static final class CountingJdbc extends JdbcTemplate {
        private int selects;
        CountingJdbc(DataSource source) { super(source); }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            selects++;
            return super.query(sql, mapper, args);
        }
    }
}
