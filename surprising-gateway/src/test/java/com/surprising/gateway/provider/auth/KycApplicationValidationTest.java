package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.service.KycDocumentService;

class KycApplicationValidationTest {
    final AuthService auth = mock();
    final CountryRepository countries = mock();
    final ComplianceKycRepository profiles = mock();
    final KycProviderRegistry providers = mock();
    final KycDocumentService documents = mock();
    final ComplianceService service = new ComplianceService(mock(), profiles, mock(), mock(), auth,
            mock(), documents, mock(), mock(), mock(), providers, countries);
    final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    KycApplicationValidationTest() {
        when(auth.authenticateBearer("token")).thenReturn(new AuthModels.JwtPrincipal(42, "test", "NORMAL", List.of("USER"), Instant.now().plusSeconds(60)));
        when(countries.supported("SG")).thenReturn(true);
        when(providers.selectedProvider()).thenReturn("SELF");
    }
    KycSubmissionRequest request(String country, LocalDate expiry, LocalDate issue, List<Long> ids) {
        return request(country, "PASSPORT", expiry, issue, null, ids);
    }
    KycSubmissionRequest request(String country, String type, LocalDate expiry, LocalDate addressIssue,
                                 LocalDate documentIssue, List<Long> ids) {
        return new KycSubmissionRequest("INDIVIDUAL", "STANDARD", country, type, "SELF", null, null,
                "NOT_REQUIRED", ids, expiry, addressIssue, documentIssue);
    }
    @Test void rejectsUnknownCountryAndMissingDocumentsBeforeProviderWork() {
        assertThatThrownBy(() -> service.submitUserKyc("token", request("ZZ", today, today, List.of(1L)))).hasMessageContaining("country");
        assertThatThrownBy(() -> service.submitUserKyc("token", request("SG", today, today, List.of()))).hasMessageContaining("required KYC");
        verify(providers, never()).start(anyLong(), any(), anyList());
        verifyNoInteractions(documents);
    }
    @Test void rejectsExpiredMissingAndFutureDates() {
        assertThatThrownBy(() -> service.submitUserKyc("token", request("SG", today.minusDays(1), today, List.of(1L)))).hasMessageContaining("expired");
        assertThatThrownBy(() -> service.submitUserKyc("token", request("SG", null, today, List.of(1L)))).hasMessageContaining("expired");
        for (LocalDate issued : List.of(today.minusMonths(3).minusDays(1), today.plusDays(1))) {
            assertThatThrownBy(() -> service.submitUserKyc("token", request("SG", today, issued, List.of(1L)))).hasMessageContaining("three months");
        }
        verify(providers, never()).start(anyLong(), any(), anyList());
        verifyNoInteractions(documents);
    }
    @Test void acceptsInclusiveThreeMonthBoundaryAndChecksRequiredDocuments() {
        when(documents.requireSubmissionDocuments(anyLong(), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("document ownership checked"));
        assertThatThrownBy(() -> service.submitUserKyc("token", request("SG", today, today.minusMonths(3), List.of(1L))))
                .hasMessage("document ownership checked");
        assertThatThrownBy(() -> service.submitUserKyc("token", request("SG", "ID_CARD", today,
                today.minusDays(1), today.minusDays(2), List.of(1L))))
                .hasMessage("document ownership checked");
        verifyNoInteractions(providers);
    }
    @Test void requiresValidIdentityCardIssueDate() {
        assertThatThrownBy(() -> service.submitUserKyc("token", request("SG", "ID_CARD", today,
                today.minusDays(1), null, List.of(1L)))).hasMessageContaining("issue date");
        assertThatThrownBy(() -> service.submitUserKyc("token", request("SG", "ID_CARD", today,
                today.minusDays(1), today.plusDays(1), List.of(1L)))).hasMessageContaining("issue date");
        verify(providers, never()).start(anyLong(), any(), anyList());
        verifyNoInteractions(documents);
    }
    @Test void pendingOrVerifiedCannotBeOverwrittenButRejectedCanResubmit() {
        for (String status : List.of("PENDING", "VERIFIED", "REJECTED")) {
            when(profiles.find(42)).thenReturn(new ComplianceModels.KycProfile(42,"STANDARD",status,"SG","PASSPORT","SELF",null,null,null,"bad image",null,Instant.now(),Instant.now(),"INDIVIDUAL","[]","NOT_REQUIRED"));
            String expected = status.equals("REJECTED") ? "required KYC" : "already pending or complete";
            assertThatThrownBy(() -> service.submitUserKyc("token",request("SG",today,today,List.of()))).hasMessageContaining(expected);
        }
        verify(providers, never()).start(anyLong(), any(), anyList());
        verifyNoInteractions(documents);
    }
}
