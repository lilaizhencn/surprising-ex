package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.SupportTicketNoteRepository;
import com.surprising.gateway.provider.auth.SupportTicketRepository;
import com.surprising.gateway.provider.auth.SupportTicketService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnabledIfEnvironmentVariable(named = "LOGIN_TEST_JDBC_URL", matches = ".+")
class SupportTicketTransactionPostgresTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private SupportTicketService service;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LOGIN_TEST_JDBC_URL");
        String user = System.getenv("LOGIN_TEST_DB_USER");
        String password = System.getenv("LOGIN_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "support_transaction_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE gateway_support_tickets (
                    ticket_id BIGSERIAL PRIMARY KEY, user_id BIGINT, status TEXT,
                    priority TEXT, category TEXT, title TEXT, assigned_admin_user_id BIGINT,
                    created_by_user_id BIGINT, resolved_by_user_id BIGINT,
                    closed_at TIMESTAMPTZ, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE gateway_support_ticket_notes (
                    note_id BIGSERIAL PRIMARY KEY, ticket_id BIGINT REFERENCES gateway_support_tickets,
                    admin_user_id BIGINT, note_type TEXT, visibility TEXT,
                    body TEXT CHECK (body <> 'FAIL'), created_at TIMESTAMPTZ)
                """);
        var auth = mock(AuthService.class);
        Instant now = Instant.now();
        when(auth.requireAdminPermission("Bearer admin", "admin.support.write"))
                .thenReturn(new AuthModels.JwtPrincipal(7, "admin", "NORMAL", List.of("ADMIN"), now.plusSeconds(60)));
        when(auth.adminUser("Bearer admin", 42))
                .thenReturn(new AuthModels.AuthenticatedUser(42, "user", "user@example.test", "NORMAL", List.of("USER"), now));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(SupportTicketService.class, () -> new SupportTicketService(
                new SupportTicketRepository(jdbc), new SupportTicketNoteRepository(jdbc), auth, mock(ComplianceService.class)));
        context.refresh();
        service = context.getBean(SupportTicketService.class);
    }

    @AfterEach
    void cleanUp() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void initialNoteFailureRollsBackTicketCreatedThroughAdminEntry() {
        assertThatThrownBy(() -> service.adminCreateTicket(
                "Bearer admin", 42, "Account review", "GENERAL", "MEDIUM", null, "FAIL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_support_tickets", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_support_ticket_notes", Integer.class)).isZero();
    }

    @Test
    void statusNoteFailureRollsBackStatusChangedThroughAdminEntry() {
        var ticket = service.adminCreateTicket("Bearer admin", 42, "Account review", "GENERAL", "MEDIUM", null, null).ticket();

        assertThatThrownBy(() -> service.adminUpdateStatus("Bearer admin", ticket.ticketId(), "CLOSED", "FAIL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_support_tickets WHERE ticket_id=?", String.class, ticket.ticketId()))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT closed_at FROM gateway_support_tickets WHERE ticket_id=?", java.sql.Timestamp.class, ticket.ticketId()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_support_ticket_notes", Integer.class)).isZero();
    }

    @Test
    void successfulAdminMutationCommitsTicketAndItsNotes() {
        var created = service.adminCreateTicket("Bearer admin", 42, "Account review", "GENERAL", "MEDIUM", null, "Initial review");
        var closed = service.adminUpdateStatus("Bearer admin", created.ticket().ticketId(), "CLOSED", "Review complete");

        assertThat(closed.ticket().status()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_support_tickets", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT body FROM gateway_support_ticket_notes ORDER BY note_id", String.class))
                .containsExactly("Initial review", "Review complete");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }
}
