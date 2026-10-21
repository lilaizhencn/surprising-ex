package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class SupportBoundaryPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions {
    }
    private static final long FIRST = 9007199254740993L;
    private static final Instant TIME = Instant.parse("2026-10-22T07:00:00.123456Z");
    private JdbcTemplate admin, jdbc;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private SupportTicketRepository tickets;
    private SupportTicketNoteRepository notes;
    private AuthService auth;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"), user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "support_page_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO gateway_users VALUES(7),(8),(9)");
        String init = Files.readString(Path.of("../init.sql"));
        for (String table : List.of("gateway_support_tickets", "gateway_support_ticket_notes")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        jdbc.update("INSERT INTO gateway_support_tickets(ticket_id,user_id,title,created_by_user_id,created_at,updated_at) "
                + "SELECT ?+g,7,'Review',9,?,? FROM generate_series(0,500) g", FIRST, Timestamp.from(TIME), Timestamp.from(TIME));
        jdbc.update("INSERT INTO gateway_support_tickets(ticket_id,user_id,title,created_by_user_id,created_at,updated_at) VALUES(?,8,'Other',9,?,?)",
                FIRST + 1000, Timestamp.from(TIME), Timestamp.from(TIME));
        jdbc.update("INSERT INTO gateway_support_ticket_notes(note_id,ticket_id,admin_user_id,body,created_at) "
                + "SELECT ?+g,?,9,'Review',? FROM generate_series(0,500) g", FIRST, FIRST, Timestamp.from(TIME));
        jdbc.update("INSERT INTO gateway_support_ticket_notes(note_id,ticket_id,admin_user_id,body,created_at) VALUES(?,?,9,'Other',?)",
                FIRST + 1000, FIRST + 1000, Timestamp.from(TIME));
        tickets = spy(new SupportTicketRepository(jdbc)); notes = spy(new SupportTicketNoteRepository(jdbc));
        auth = mock(AuthService.class);
        for (long id : List.of(8L, 9L)) {
            when(auth.requireAdminPermission(eq("Bearer " + id), anyString())).thenReturn(new JwtPrincipal(id, "support", "NORMAL", List.of("SUPPORT"), Instant.now().plusSeconds(3600)));
            when(auth.adminUser("Bearer " + id, 7)).thenReturn(new AuthenticatedUser(7, "user", null, "NORMAL", List.of("USER"), TIME));
        }
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(SupportTicketService.class, () -> new SupportTicketService(tickets, notes, auth, mock(ComplianceService.class)));
        context.refresh();
        mvc = MockMvcBuilders.standaloneSetup(new AdminSupportController(context.getBean(SupportTicketService.class))).build();
    }
    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @ParameterizedTest @ValueSource(strings = {"createdAt.asc", "createdAt.desc", "updatedAt.asc", "updatedAt.desc"})
    void ticketPagesKeepExactLongMicrosecondsAndUserStatusScope(String sort) throws Exception {
        long expected = sort.endsWith("asc") ? FIRST : FIRST + 500;
        JsonNode page = read(ticketPage().param("sort", sort).param("limit", "1"));
        assertThat(page.path("tickets").get(0).path("ticketId").asLong()).isEqualTo(expected);
        JsonNode next = read(ticketPage().param("sort", sort).param("limit", "1").param("cursor", page.path("nextCursor").asString()));
        assertThat(next.path("tickets").get(0).path("ticketId").asLong()).isEqualTo(expected + (sort.endsWith("asc") ? 1 : -1));
        assertThat(next.path("tickets").get(0).path("userId").asLong()).isEqualTo(7);
    }

    @ParameterizedTest @ValueSource(strings = {"createdAt.asc", "createdAt.desc"})
    void notePagesKeepTicketScopeAndStableTimestampTies(String sort) throws Exception {
        long expected = sort.endsWith("asc") ? FIRST : FIRST + 500;
        JsonNode page = read(notePage().param("sort", sort).param("limit", "1"));
        assertThat(page.path("notes").get(0).path("noteId").asLong()).isEqualTo(expected);
        JsonNode next = read(notePage().param("sort", sort).param("limit", "1").param("cursor", page.path("nextCursor").asString()));
        assertThat(next.path("notes").get(0).path("noteId").asLong()).isEqualTo(expected + (sort.endsWith("asc") ? 1 : -1));
        assertThat(next.path("notes").get(0).path("ticketId").asLong()).isEqualTo(FIRST);
    }

    @Test void ticketAndNoteLimitsKeepTheirDifferentHttpPolicies() throws Exception {
        mvc.perform(ticketPage().param("limit", "0")).andExpect(status().isBadRequest());
        assertThat(read(ticketPage().param("limit", "2147483647")).path("ticketCount").asInt()).isEqualTo(100);
        assertThat(read(notePage().param("limit", "0")).path("noteCount").asInt()).isEqualTo(1);
        assertThat(read(notePage().param("limit", "2147483647")).path("noteCount").asInt()).isEqualTo(500);
    }

    @Test void normalizedWritesAndReopeningRetainLastResolverButClearClosure() throws Exception {
        JsonNode created = read(post("/api/v1/admin/support/users/7/tickets").header("Authorization", "Bearer 9")
                .contentType("application/json").content(json.writeValueAsString(new AdminSupportController.CreateSupportTicketRequest(" Review ", "withdrawal", "high", null, " Initial note "))));
        long id = created.path("ticket").path("ticketId").asLong();
        assertThat(created.path("ticket").path("title").asString()).isEqualTo("Review");
        assertThat(created.path("ticket").path("category").asString()).isEqualTo("WITHDRAWAL");
        assertThat(created.path("ticket").path("priority").asString()).isEqualTo("HIGH");
        assertThat(created.path("notes").get(0).path("body").asString()).isEqualTo("Initial note");
        update(id, "closed", 9);
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM gateway_support_tickets WHERE ticket_id=?", Boolean.class, id)).isTrue();
        update(id, "resolved", 8); update(id, "open", 9);
        assertThat(jdbc.queryForObject("SELECT resolved_by_user_id FROM gateway_support_tickets WHERE ticket_id=?", Long.class, id)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT closed_at IS NULL FROM gateway_support_tickets WHERE ticket_id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_support_ticket_notes WHERE ticket_id=?", Integer.class, id)).isEqualTo(4);
    }

    @ParameterizedTest @ValueSource(strings = {"sort", "cursor", "timestamp", "status", "title", "priority", "category", "noteType", "visibility"})
    void malformedInputIsRejectedBeforeRepositories(String invalid) throws Exception {
        var request = ticketPage();
        switch (invalid) {
            case "sort" -> request.param("sort", "updatedAt DESC;DROP TABLE gateway_users");
            case "cursor" -> request.param("cursor", "***");
            case "timestamp" -> request.param("cursor", java.util.Base64.getUrlEncoder().encodeToString("bad-time|1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            case "status" -> request = get("/api/v1/admin/support/tickets").header("Authorization", "Bearer 9").param("status", "bogus");
            case "title", "priority", "category" -> request = post("/api/v1/admin/support/users/7/tickets").header("Authorization", "Bearer 9").contentType("application/json")
                    .content(json.writeValueAsString(new AdminSupportController.CreateSupportTicketRequest(invalid.equals("title") ? " " : "Review", invalid.equals("category") ? "bad category!" : "GENERAL", invalid.equals("priority") ? "bad" : "MEDIUM", null, null)));
            default -> request = post("/api/v1/admin/support/tickets/" + FIRST + "/notes").header("Authorization", "Bearer 9").contentType("application/json")
                    .content(json.writeValueAsString(new AdminSupportController.SupportTicketNoteRequest(invalid.equals("noteType") ? "bad" : "NOTE", invalid.equals("visibility") ? "bad" : "INTERNAL", "Review")));
        }
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(tickets, notes);
    }

    @Test void deniedPermissionNeverReadsTicketTable() throws Exception {
        when(auth.requireAdminPermission("Bearer 9", "admin.support.read")).thenThrow(new IllegalStateException("denied"));
        mvc.perform(ticketPage()).andExpect(status().isForbidden()); verifyNoInteractions(tickets, notes);
    }

    private void update(long id, String state, long actor) throws Exception {
        read(post("/api/v1/admin/support/tickets/" + id + "/status").header("Authorization", "Bearer " + actor)
                .contentType("application/json").content(json.writeValueAsString(new AdminSupportController.SupportTicketStatusRequest(" " + state + " ", " Review "))));
    }
    private MockHttpServletRequestBuilder ticketPage() {
        return get("/api/v1/admin/support/tickets").header("Authorization", "Bearer 9").param("userId", "7").param("status", " open ");
    }
    private MockHttpServletRequestBuilder notePage() {
        return get("/api/v1/admin/support/tickets/" + FIRST + "/notes").header("Authorization", "Bearer 9");
    }
    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
}
