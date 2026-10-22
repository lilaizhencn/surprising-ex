package com.surprising.trading.order.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.local.LocalBusinessApi;
import com.surprising.gateway.provider.local.TradingLocalRoutes;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.model.*;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.service.*;
import jakarta.validation.Validation;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual local HTTP dispatch -> request Controller -> Service -> PostgreSQL; no database fee fallback. */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class FeeScheduleBoundaryPostgresTest {
    private static final long FIRST = 9007199254740993L;
    private static final Instant TIME = Instant.parse("2026-10-22T07:00:00.123456Z");
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate admin, jdbc;
    private String schema;
    private OrderFeeRepository repository;
    private FeeScheduleEventPublisher publisher;
    private FeePolicyCoreImporter importer;
    private TradingFeeService service;
    private LocalBusinessApi api;
    private ProductLine product;
    private jakarta.validation.ValidatorFactory validation;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "fee_boundary_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, user, password));
        String init = Files.readString(Path.of("../init.sql"));
        int start = init.indexOf("CREATE TABLE IF NOT EXISTS trading_fee_schedules (");
        jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        repository = new OrderFeeRepository(jdbc);
        for (ProductLine line : ProductLine.values()) {
            long id = first(line);
            for (int i = 0; i < 3; i++) repository.project(new FeeScheduleResponse(id + i, line, 7, "1", -50, 350,
                    FeeScheduleSourceType.VIP, "VIP3", "test", FeeScheduleStatus.ACTIVE,
                    TIME.plusNanos(i == 0 ? 1000 : 0), null,
                    TIME.plusNanos(i == 2 ? 1000 : 0), TIME.plusNanos(i == 1 ? 1000 : 0)));
            repository.project(fact(id + 3, line, 8, "1", FeeScheduleStatus.ACTIVE));
            repository.project(fact(id + 4, line, 7, "2", FeeScheduleStatus.ACTIVE));
            repository.project(fact(id + 5, line, 7, "1", FeeScheduleStatus.DISABLED));
        }
        configure(ProductLine.LINEAR_PERPETUAL);
    }

    private void configure(ProductLine line) {
        product = line;
        publisher = mock(FeeScheduleEventPublisher.class);
        importer = mock(FeePolicyCoreImporter.class);
        var properties = new TradingOrderProperties(); properties.getKafka().setProductLine(line);
        service = new TradingFeeService(repository, null, null, publisher, new FeeScheduleSnapshotCache(), properties, importer);
        var controller = new TradingFeeRequestController(service);
        var routes = new TradingLocalRoutes(null, null, null, null, controller, null, null, null, null);
        if (validation != null) validation.close();
        validation = Validation.buildDefaultValidatorFactory();
        api = new LocalBusinessApi(routes, null, null, json, validation.getValidator(),
                null, null, properties);
    }

    @AfterEach void cleanup() { if (validation != null) validation.close(); if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    static Stream<Arguments> pages() {
        return Arrays.stream(ProductLine.values()).flatMap(line -> Stream.of("updatedAt", "createdAt", "effectiveTime")
                .flatMap(field -> Stream.of("asc", "desc").map(direction -> Arguments.of(line, field + "." + direction))));
    }

    @ParameterizedTest @MethodSource("pages")
    void pagesKeepAllSixProductsExactLongsMicrosecondsAndTies(ProductLine line, String sort) throws Exception {
        configure(line);
        var response = query("&limit=2&sort=" + sort);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var firstPage = json.readTree(response.getBody());
        assertThat(firstPage.get("hasMore").asBoolean()).isTrue();
        String cursor = firstPage.get("nextCursor").asString();
        assertThat(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)).contains(".12345");
        var secondPage = json.readTree(query("&limit=2&sort=" + sort + "&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8)).getBody());
        List<Long> actual = new ArrayList<>();
        for (JsonNode page : List.of(firstPage, secondPage)) for (JsonNode row : page.get("schedules")) {
            actual.add(row.get("feeScheduleId").asLong());
            assertThat(row.get("productLine").asString()).isEqualTo(line.name());
            assertThat(row.get("userId").asLong()).isEqualTo(7);
            assertThat(row.get("instrumentId").asString()).isEqualTo("1");
        }
        long id = first(line);
        List<Long> expected = switch (sort) {
            case "createdAt.asc" -> List.of(id, id + 1, id + 2);
            case "createdAt.desc" -> List.of(id + 2, id + 1, id);
            case "effectiveTime.asc" -> List.of(id + 1, id + 2, id);
            case "effectiveTime.desc" -> List.of(id, id + 2, id + 1);
            case "updatedAt.asc" -> List.of(id, id + 2, id + 1);
            default -> List.of(id + 1, id + 2, id);
        };
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(secondPage.get("hasMore").asBoolean()).isFalse();
        assertThat(secondPage.get("nextCursor").isNull()).isTrue();
        verifyNoInteractions(publisher, importer);
    }

    @ParameterizedTest @CsvSource({"0,100", "-1,100", "1,1", "999,500"})
    void pageLimitRetainsDefaultAndClamp(int input, int expected) throws Exception {
        var page = json.readTree(query("&limit=" + input).getBody());
        assertThat(page.get("limit").asInt()).isEqualTo(expected);
        assertThat(page.get("sort").asString()).isEqualTo("updatedAt.desc");
    }

    @ParameterizedTest @ValueSource(strings = {"&sort=updatedAt;drop", "&sort=userId.asc", "&cursor=broken",
            "&cursor=YmFkfDE", "&instrumentId=bad", "&instrumentId=2147483648"})
    void malformedQueryReturns400(String suffix) {
        var response = suffix.startsWith("&instrumentId=")
                ? invoke("?userId=7&status=ACTIVE" + suffix, HttpMethod.GET, null) : query(suffix);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void rejectsOtherProductsBeforeReadingOrPublishing(ProductLine line) {
        configure(line);
        ProductLine other = ProductLine.values()[(line.ordinal() + 1) % ProductLine.values().length];
        assertThat(invoke("?productLine=" + other.name() + "&userId=7", HttpMethod.GET, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invoke("", HttpMethod.POST, request(other, -50, 350, TIME, null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(publisher, importer);
    }

    @Test void upsertBindsNativeFieldsKeepsRebateAndPublishesBeforeCoreWithoutDatabaseWrite() throws Exception {
        var request = new FeeScheduleUpsertRequest(Long.MAX_VALUE, null, 7, " 1 ", -1_000_000, 1_000_000,
                null, " vip3 ", "  review  ", null, TIME, TIME.plusSeconds(1));
        var response = invoke("", HttpMethod.POST, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = json.readTree(response.getBody());
        assertThat(result.get("feeScheduleId").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.get("tierCode").asString()).isEqualTo("vip3");
        assertThat(result.get("reason").asString()).isEqualTo("review");
        assertThat(result.get("instrumentId").asString()).isEqualTo("1");
        assertThat(result.get("makerFeeRatePpm").asLong()).isEqualTo(-1_000_000);
        assertThat(result.get("sourceType").asString()).isEqualTo("USER_OVERRIDE");
        var facts = org.mockito.ArgumentCaptor.forClass(FeeScheduleResponse.class);
        var order = inOrder(publisher, importer);
        order.verify(publisher).publish(facts.capture()); order.verify(importer).importPolicy(facts.getValue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_fee_schedules WHERE fee_schedule_id=?", Long.class, Long.MAX_VALUE)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"maker", "expiry"})
    void crossFieldPoliciesAreEnforcedByService(String invalid) {
        var request = invalid.equals("maker") ? request(product, 500, 400, TIME, null)
                : request(product, -50, 350, TIME, TIME);
        assertThatThrownBy(() -> service.upsertSchedule(request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(invalid.equals("maker") ? "makerFeeRatePpm cannot exceed takerFeeRatePpm" : "expireTime must be after effectiveTime");
        assertThat(invoke("", HttpMethod.POST, request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(publisher, importer);
    }

    @ParameterizedTest @ValueSource(strings = {"feeScheduleId", "userId", "instrumentId", "makerFeeRatePpm", "takerFeeRatePpm", "reason", "tierCode"})
    void malformedWritesNeverPublishOrImport(String field) {
        var request = json.valueToTree(request(product, -50, 350, TIME, null)).deepCopy();
        var node = (tools.jackson.databind.node.ObjectNode) request;
        switch (field) {
            case "feeScheduleId", "userId" -> node.put(field, 0L);
            case "makerFeeRatePpm" -> node.put(field, -1_000_001L);
            case "takerFeeRatePpm" -> node.put(field, Long.MAX_VALUE);
            case "reason" -> node.put(field, " ");
            default -> node.put(field, "invalid!");
        }
        assertThat(invoke("", HttpMethod.POST, node).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(publisher, importer);
    }

    @Test void publicationAndCoreImportFailureKeepOriginalOrder() {
        var request = request(product, -50, 350, TIME, null);
        doThrow(new IllegalStateException("publish failed")).when(publisher).publish(any());
        assertThatThrownBy(() -> service.upsertSchedule(request)).hasMessage("publish failed");
        verifyNoInteractions(importer);
        reset(publisher);
        doThrow(new IllegalStateException("Core not confirmed")).when(importer).importPolicy(any());
        assertThatThrownBy(() -> service.upsertSchedule(request)).hasMessage("Core not confirmed");
        var order = inOrder(publisher, importer); order.verify(publisher).publish(any()); order.verify(importer).importPolicy(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_fee_schedules WHERE fee_schedule_id=?", Long.class, Long.MAX_VALUE)).isZero();
    }

    private ResponseEntity<byte[]> query(String suffix) { return invoke("?userId=7&instrumentId=%201%20&status=ACTIVE" + suffix, HttpMethod.GET, null); }
    private ResponseEntity<byte[]> invoke(String suffix, HttpMethod method, Object body) {
        var headers = new HttpHeaders(); headers.set("X-Admin-User-Id", "9"); headers.set("X-Product-Line", product.name());
        return api.invoke("trading-fees", URI.create("http://local/api/v1/admin/trading/fees/schedules" + suffix), method,
                headers, body == null ? null : json.writeValueAsBytes(body), Duration.ofSeconds(3));
    }
    private static long first(ProductLine product) { return FIRST + product.ordinal() * 10L; }
    private static FeeScheduleResponse fact(long id, ProductLine product, long user, String symbol, FeeScheduleStatus status) {
        return new FeeScheduleResponse(id, product, user, symbol, -50, 350, FeeScheduleSourceType.VIP, "VIP3", "test", status,
                TIME, null, TIME, TIME);
    }
    private static FeeScheduleUpsertRequest request(ProductLine product, long maker, long taker, Instant effective, Instant expiry) {
        return new FeeScheduleUpsertRequest(Long.MAX_VALUE, product, 7, "1", maker, taker, FeeScheduleSourceType.VIP, "VIP3", "review",
                FeeScheduleStatus.ACTIVE, effective, expiry);
    }
}
