package com.surprising.gateway.provider.auth;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
class KycEvidenceReferenceTest {
    @Test void persistsSeparatedIdentityCardEvidenceWithDates() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new ComplianceKycRepository(jdbc, new ObjectMapper());
        var request = new ComplianceModels.KycSubmissionRequest("INDIVIDUAL", "STANDARD", "SG", "ID_CARD", "SELF", null, null, "NOT_REQUIRED", List.of(1L, 2L, 3L, 4L));
        String references = """
                [{"type":"ID_CARD_FRONT","reference":"document:1","documentExpiresOn":"2030-01-01"},
                 {"type":"ID_CARD_BACK","reference":"document:2"},
                 {"type":"ID_CARD_SELFIE","reference":"document:3"},
                 {"type":"ADDRESS_PROOF","reference":"document:4","addressIssuedOn":"2026-10-01"}]
                """;
        assertThatCode(() -> repository.submit(42, request, references, Instant.now())).doesNotThrowAnyException();
        assertThatThrownBy(() -> repository.submit(42, request,
                "[{\"type\":\"UNKNOWN\",\"reference\":\"document:1\"}]", Instant.now()))
                .hasMessageContaining("type or reference is invalid");
    }
}
