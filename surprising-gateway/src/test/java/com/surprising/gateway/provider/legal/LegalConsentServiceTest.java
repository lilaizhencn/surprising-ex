package com.surprising.gateway.provider.legal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.model.LegalDocumentType;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import com.surprising.gateway.provider.legal.repository.*;
import com.surprising.gateway.provider.legal.service.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LegalConsentServiceTest {
    private final LegalRevisionRepository revisions=mock(LegalRevisionRepository.class);
    private final LegalTranslationRepository translations=mock(LegalTranslationRepository.class);
    private final LegalConsentRepository consents=mock(LegalConsentRepository.class);
    private final LegalDocumentService documents=mock(LegalDocumentService.class);
    private final LegalConsentService service=new LegalConsentService(documents,revisions,translations,consents);
    private final Instant now=Instant.parse("2026-10-31T00:00:00Z");
    private Document document(long id,LegalDocumentType type) { return new Document(id,type,type.slug(),"1.0","en-US","Title","<p>Text</p>",true,now); }
    private RevisionRow row(long id,LegalDocumentType type,String status) { return new RevisionRow(id,type,"1.0",status,1,9,9,now,now,now); }
    private void current() {
        when(documents.required("en-US")).thenReturn(List.of(document(1,LegalDocumentType.USER_AGREEMENT),document(2,LegalDocumentType.PRIVACY_POLICY)));
        when(documents.requiredRevisionIds()).thenReturn(List.of(1L,2L));
        when(revisions.find(1)).thenReturn(Optional.of(row(1,LegalDocumentType.USER_AGREEMENT,"PUBLISHED")));
        when(revisions.find(2)).thenReturn(Optional.of(row(2,LegalDocumentType.PRIVACY_POLICY,"PUBLISHED")));
        for(long id:List.of(1L,2L))when(translations.hasLocale(id,"en-US")).thenReturn(true);
    }
    @Test void registrationCannotInferConsentFromVisitingAProtocolOrFromAnEmptyHeader() {
        current();assertThatThrownBy(()->service.validateRegistration(List.of())).isInstanceOf(LegalProblem.class).hasMessageContaining("review and acknowledge");
        verifyNoInteractions(consents);
    }
    @Test void duplicateOrObsoleteRevisionNeverCreatesAConsentRecord() {
        current();
        assertThatThrownBy(()->service.acknowledge(42,List.of(new Acceptance(1,"en-US"),new Acceptance(1,"en-US")),"en-US")).isInstanceOf(LegalProblem.class);
        when(revisions.find(1)).thenReturn(Optional.of(row(1,LegalDocumentType.USER_AGREEMENT,"SUPERSEDED")));
        assertThatThrownBy(()->service.acknowledge(42,List.of(new Acceptance(1,"en-US")),"en-US")).isInstanceOf(LegalProblem.class).hasMessageContaining("changed");
        verify(consents,never()).insert(anyLong(),anyList(),any());
    }
    @Test void acceptanceRecordsTheActualAvailableLanguageAndRejectsMissingTranslations() {
        current();
        assertThatThrownBy(()->service.acknowledge(42,List.of(new Acceptance(1,"ja-JP")),"en-US")).isInstanceOf(LegalProblem.class).hasMessageContaining("language is unavailable");
        assertThatThrownBy(()->service.acknowledge(42,List.of(new Acceptance(1,null)),"en-US")).isInstanceOf(LegalProblem.class).hasMessageContaining("language");
        verify(consents,never()).insert(anyLong(),anyList(),any());
    }
    @Test void existingCurrentAcknowledgementsDoNotRequireInventingNewChoices() {
        current();when(consents.findFor(42,List.of(1L,2L))).thenReturn(List.of(new Consent(1,"en-US",now),new Consent(2,"en-US",now)));
        service.recordAndRequire(42,List.of(),now);
        verify(consents,never()).insert(anyLong(),anyList(),any());
        verify(documents,never()).required(anyString());
        verify(translations,never()).findFor(anyList());
    }
    @Test void newDocumentVersionRemainsMissingUntilThatVersionIsConfirmed() {
        current();when(consents.findFor(42,List.of(1L,2L))).thenReturn(List.of(new Consent(2,"en-US",now)));
        assertThat(service.status(42,"en-US").missingRevisionIds()).containsExactly(1L);
        assertThatThrownBy(()->service.recordAndRequire(42,List.of(),now)).isInstanceOf(LegalProblem.class);
    }
}
