package com.surprising.derivatives.lifecycle;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.controller.AdlController;
import com.surprising.adl.provider.repository.AdlEventRepository;
import com.surprising.adl.provider.service.AdlAeronGateway;
import com.surprising.adl.provider.service.AdlService;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.controller.InsuranceController;
import com.surprising.insurance.provider.repository.InsuranceCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceFundLedgerRepository;
import com.surprising.insurance.provider.service.InsuranceAeronGateway;
import com.surprising.insurance.provider.service.InsuranceService;
import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual HTTP controllers, native business product scope and PostgreSQL historical reads. */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class LifecycleHistoryBoundaryPostgresTest {
    private static final long FIRST = 9007199254740993L;
    private static final Instant TIME = Instant.parse("2026-10-22T07:00:00.123456Z");
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate admin, jdbc;
    private String schema;
    private MockMvc mvc;
    private AdlAeronGateway adlCore;
    private InsuranceAeronGateway insuranceCore;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "lifecycle_history_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        String init = Files.readString(Path.of("../..", "init.sql"));
        for (String table : List.of("adl_events", "insurance_deficit_coverages", "insurance_fund_ledger")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        for (ProductLine line : ProductLine.values()) if (line != ProductLine.SPOT) {
            long id = first(line);
            for (int i = 0; i < 3; i++) seed(id + i, line.accountTypeCode(), i == 1 ? 8 : 7, i == 1 ? 7 : 9,
                    "USDT", "1", i == 2 ? TIME.plusNanos(1000) : TIME);
            seed(id + 3, line.accountTypeCode(), 10, 11, "BTC", "2", TIME);
        }
        configure(ProductLine.LINEAR_PERPETUAL);
    }

    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    private void configure(ProductLine line) {
        var ap = new AdlProperties(); ap.getKafka().setProductLine(line);
        var ip = new InsuranceProperties(); ip.getKafka().setProductLine(line);
        adlCore = mock(AdlAeronGateway.class); insuranceCore = mock(InsuranceAeronGateway.class);
        var adl = new AdlService(ap, adlCore, new AdlEventRepository(jdbc));
        var insurance = new InsuranceService(ip, new InsuranceFundLedgerRepository(jdbc), new InsuranceCoverageRepository(jdbc), insuranceCore);
        mvc = MockMvcBuilders.standaloneSetup(new AdlController(adl, null), new InsuranceController(insurance, null)).build();
    }

    static Stream<Arguments> pages() {
        return Arrays.stream(ProductLine.values()).filter(line -> line != ProductLine.SPOT).flatMap(line -> Stream.of("adl", "coverage", "ledger")
                .flatMap(kind -> Stream.of("asc", "desc").map(direction -> Arguments.of(line, kind, direction))));
    }

    @ParameterizedTest @MethodSource("pages")
    void pagesKeepProductAndExactHistoryCoordinates(ProductLine line, String kind, String direction) throws Exception {
        configure(line);
        JsonNode first = page(kind, direction, null);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("limit").asInt()).isEqualTo(2);
        assertThat(first.get("sort").asString()).isEqualTo("createdAt." + direction);
        JsonNode second = page(kind, direction, first.get("nextCursor").asString());
        List<Long> ids = new ArrayList<>();
        for (JsonNode page : List.of(first, second)) for (JsonNode row : page.get(items(kind))) {
            ids.add(row.get(idField(kind)).asLong());
            assertThat(row.get("asset").asString()).isEqualTo("USDT");
        }
        long id = first(line);
        assertThat(ids).containsExactlyElementsOf(direction.equals("asc") ? List.of(id, id + 1, id + 2) : List.of(id + 2, id + 1, id));
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        verifyNoInteractions(adlCore, insuranceCore);
    }

    @ParameterizedTest @ValueSource(strings = {"adl", "coverage", "ledger"})
    void publicHistoryUsesTheSameBoundDefaults(String kind) throws Exception {
        String path = path(kind).replace("/admin", "");
        JsonNode page = json.readTree(mvc.perform(get(path).param("asset", " usdt ").param("limit", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(page.get("limit").asInt()).isEqualTo(2);
        assertThat(page.get("sort").asString()).isEqualTo("createdAt.desc");
        assertThat(page.get(items(kind)).size()).isEqualTo(2);
        verifyNoInteractions(adlCore, insuranceCore);
    }

    static Stream<Arguments> invalid() {
        return Stream.of("adl", "coverage", "ledger").flatMap(kind -> Stream.of(
                Arguments.of(kind, "limit", "0"), Arguments.of(kind, "limit", "1001"),
                Arguments.of(kind, "cursor", "broken"), Arguments.of(kind, "sort", "createdAt;drop"),
                Arguments.of(kind, "asset", "bad!")).map(a -> a));
    }
    @ParameterizedTest @MethodSource("invalid")
    void invalidInputsAreRejectedByTheActualHttpBoundary(String kind, String parameter, String value) throws Exception {
        mvc.perform(get(path(kind)).header("X-Admin-User-Id", "9").param(parameter, value))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adlCore, insuranceCore);
    }

    @Test void adlUserFilterMatchesEitherParticipantAndCoverageStillUsesTheOwner() throws Exception {
        JsonNode adl = json.readTree(mvc.perform(get(path("adl")).header("X-Admin-User-Id", "9").param("userId", "7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(adl.get("events").size()).isEqualTo(3);
        JsonNode coverage = json.readTree(mvc.perform(get(path("coverage")).header("X-Admin-User-Id", "9").param("userId", "7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(coverage.get("coverages").size()).isEqualTo(2);
        mvc.perform(get(path("coverage")).header("X-Admin-User-Id", "9").param("userId", "0"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode page(String kind, String direction, String cursor) throws Exception {
        var request = get(path(kind)).header("X-Admin-User-Id", "9").param("asset", " usdt ")
                .param("limit", "2").param("sort", "createdAt." + direction);
        if (kind.equals("adl")) request.param("instrumentId", " 1 ").param("userId", "7");
        if (cursor != null) request.param("cursor", cursor);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private static String path(String kind) { return kind.equals("adl") ? "/api/v1/adl/admin/events" : "/api/v1/insurance/admin/" + (kind.equals("coverage") ? "coverages" : "ledger"); }
    private static String items(String kind) { return kind.equals("adl") ? "events" : kind.equals("coverage") ? "coverages" : "entries"; }
    private static String idField(String kind) { return kind.equals("adl") ? "eventId" : kind.equals("coverage") ? "coverageId" : "entryId"; }
    private static long first(ProductLine line) { return FIRST + line.ordinal() * 10; }

    private void seed(long id, String account, long deficitUser, long targetUser, String asset, String instrument, Instant time) {
        Timestamp now = Timestamp.from(time);
        jdbc.update("INSERT INTO adl_events(event_id,account_type,deficit_user_id,target_user_id,asset,instrument_id,target_side,closed_quantity_steps,entry_price_ticks,mark_price_ticks,requested_deficit_units,realized_profit_units,covered_units,remaining_deficit_units,priority_score_ppm,created_at) VALUES(?,?,?,?,?,?,'LONG',1,1,1,100,100,100,0,1,?)", id, account, deficitUser, targetUser, asset, instrument, now);
        jdbc.update("INSERT INTO insurance_deficit_coverages(coverage_id,account_type,user_id,asset,requested_units,covered_units,remaining_deficit_units,reserve_command_id,finalize_command_id,status,completed_at,created_at,updated_at) VALUES(?,?,?,?,100,100,0,?,?,'COVERED',?,?,?)", id, account, deficitUser, asset, "reserve:" + id, "finalize:" + id, now, now, now);
        jdbc.update("INSERT INTO insurance_fund_ledger(entry_id,account_type,asset,amount_units,balance_after_units,reference_type,reference_id,created_at) VALUES(?,?,?,100,100,'TEST',?,?)", id, account, asset, "ref:" + id, now);
    }
}
