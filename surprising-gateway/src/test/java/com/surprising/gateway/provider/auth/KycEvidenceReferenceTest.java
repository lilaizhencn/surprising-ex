package com.surprising.gateway.provider.auth;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.KycDocumentService;
class KycEvidenceReferenceTest {
    @Test void storesIdentityCardIssueAndExpiryDatesWithItsEvidence() {
        var service = new KycDocumentService(mock(), mock(), new GatewayProperties(), new ObjectMapper());
        var document = new KycDocument(1, 42, "ID_CARD_FRONT", "front.png", "image/png", 10,
                "hash", "UPLOADED", Instant.now(), null);

        String references = service.references(List.of(document), LocalDate.parse("2031-01-01"), null,
                LocalDate.parse("2020-02-03"));

        assertThat(references).contains("\"documentIssuedOn\":\"2020-02-03\"")
                .contains("\"documentExpiresOn\":\"2031-01-01\"");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DRIVING_LICENSE", "RESIDENCE_PERMIT"})
    void persistsAdditionalIdentityEvidence(String type) {
        var repository = new ComplianceKycRepository(mock(JdbcTemplate.class), new ObjectMapper());
        var request = new ComplianceModels.KycSubmissionRequest("INDIVIDUAL", "BASIC", "SG", type, "SELF", null, null, "NOT_REQUIRED", List.of(1L, 2L));
        assertThatCode(() -> repository.submit(42, request,
            "[{\"type\":\"" + type + "_FRONT\",\"reference\":\"document:1\"},{\"type\":\"" + type + "_BACK\",\"reference\":\"document:2\"}]", Instant.now())).doesNotThrowAnyException();
    }
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
