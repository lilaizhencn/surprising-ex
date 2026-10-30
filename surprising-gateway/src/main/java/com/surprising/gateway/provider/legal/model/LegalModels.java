package com.surprising.gateway.provider.legal.model;

import java.time.Instant;
import java.util.List;

public final class LegalModels {
    private LegalModels() { }
    public record TranslationInput(String locale,String title,String bodyHtml) { }
    public record Translation(String locale,String title,String bodyHtml) { }
    public record SaveRequest(LegalDocumentType type,String revisionLabel,List<TranslationInput> translations,Long version) { }
    public record PublishRequest(Long version,String reason) { }
    public record Content(LegalDocumentType type,String revisionLabel,List<Translation> translations) {
        public Content { translations=List.copyOf(translations); }
    }
    public record RevisionRow(long revisionId,LegalDocumentType type,String revisionLabel,String status,long version,
                              long createdBy,long updatedBy,Instant createdAt,Instant updatedAt,Instant publishedAt) { }
    public record Revision(long revisionId,LegalDocumentType type,String revisionLabel,String status,long version,
                           List<Translation> translations,Instant createdAt,Instant updatedAt,Instant publishedAt) { }
    public record AdminPage(List<RevisionRow> revisions,int offset,int limit,boolean hasMore) { }
    public record TranslationRow(long revisionId,Translation translation) { }
    public record Document(long revisionId,LegalDocumentType type,String slug,String revisionLabel,
                           String locale,String title,String bodyHtml,boolean registrationRequired,Instant publishedAt) { }
    public record Acceptance(long revisionId,String locale) { }
    public record ConsentRequest(List<Acceptance> documents) { }
    public record Consent(long revisionId,String locale,Instant acknowledgedAt) { }
    public record ConsentStatus(List<Document> documents,List<Long> missingRevisionIds) { }
    public record Audit(long auditId,long revisionId,String action,long adminUserId,String reason,String snapshot,Instant createdAt) { }
}
