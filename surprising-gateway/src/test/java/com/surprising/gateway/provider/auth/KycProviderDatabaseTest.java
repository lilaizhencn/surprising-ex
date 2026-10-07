package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "LOGIN_TEST_JDBC_URL", matches = ".+")
class KycProviderDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private ComplianceKycRepository repository;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LOGIN_TEST_JDBC_URL");
        String user = System.getenv("LOGIN_TEST_DB_USER");
        String password = System.getenv("LOGIN_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "kyc_provider_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE gateway_user_kyc_profiles (
                    user_id BIGINT PRIMARY KEY REFERENCES gateway_users(user_id), kyc_level TEXT NOT NULL,
                    status TEXT NOT NULL, country TEXT, document_type TEXT, provider TEXT, provider_reference TEXT,
                    reviewed_by_user_id BIGINT, reviewed_at TIMESTAMPTZ, rejection_reason TEXT, expires_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
                    applicant_type TEXT NOT NULL DEFAULT 'INDIVIDUAL', submitted_documents JSONB NOT NULL DEFAULT '[]',
                    face_verification_status TEXT NOT NULL DEFAULT 'NOT_REQUIRED')
                """);
        jdbc.execute("""
                CREATE TABLE gateway_kyc_provider_events (
                    provider TEXT NOT NULL, event_key TEXT NOT NULL, received_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY(provider, event_key))
                """);
        jdbc.update("INSERT INTO gateway_users(user_id) VALUES (1)");
        repository = new ComplianceKycRepository(jdbc, new ObjectMapper());
    }

    @AfterEach
    void cleanUp() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test
    void persistsProviderSessionDeduplicatesWebhookAndAppliesVerifiedDecision() {
        Instant now = Instant.now();
        var submitted = repository.submit(1L,
                new KycSubmissionRequest("INDIVIDUAL", "STANDARD", "SG", "PASSPORT", "SUMSUB",
                        "applicant-1", null, "PENDING", java.util.List.of()), "[]", now);
        assertThat(submitted.status()).isEqualTo("PENDING");
        assertThat(submitted.provider()).isEqualTo("SUMSUB");
        assertThat(repository.recordProviderEvent("SUMSUB", "event-1", now)).isTrue();
        assertThat(repository.recordProviderEvent("SUMSUB", "event-1", now)).isFalse();
        var verified = repository.applyProviderResult(1L, "SUMSUB", "VERIFIED", null, now.plusSeconds(1));
        assertThat(verified.status()).isEqualTo("VERIFIED");
        assertThat(verified.faceVerificationStatus()).isEqualTo("VERIFIED");
    }
}
