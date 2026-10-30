package com.surprising.gateway.provider.legal.service;

import com.surprising.gateway.provider.content.model.ContentLocale;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.exception.LegalProblem.Kind;
import com.surprising.gateway.provider.legal.model.LegalDocumentType;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import com.surprising.gateway.provider.legal.repository.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Public legal text reads are independent of authentication so session creation has no circular dependency. */
@Service
public class LegalDocumentService {
    private final LegalRevisionRepository revisions;
    private final LegalTranslationRepository translations;
    private final com.surprising.gateway.provider.content.service.ArticleRenderingService renderer;
    public LegalDocumentService(LegalRevisionRepository revisions,LegalTranslationRepository translations,com.surprising.gateway.provider.content.service.ArticleRenderingService renderer) { this.revisions=revisions;this.translations=translations;this.renderer=renderer; }
    public List<Document> current(String locale) {
        String requested=ContentLocale.require(locale).code();
        var rows=revisions.current();
        var byId=translations.findFor(rows.stream().map(RevisionRow::revisionId).toList()).stream()
                .collect(Collectors.groupingBy(TranslationRow::revisionId,Collectors.mapping(TranslationRow::translation,Collectors.toList())));
        return rows.stream().map(row->{
            var translation=byId.getOrDefault(row.revisionId(),List.of()).stream().filter(t->t.locale().equals(requested)).findFirst()
                    .orElseThrow(()->new LegalProblem(Kind.NOT_READY,"The requested legal translation is temporarily unavailable."));
            return new Document(row.revisionId(),row.type(),row.type().slug(),row.revisionLabel(),translation.locale(),translation.title(),renderer.render(List.of(translation.bodyHtml())).get(translation.bodyHtml()),row.type().registrationRequired(),row.publishedAt());
        }).toList();
    }
    public List<Document> required(String locale) {
        var documents=current(locale).stream().filter(Document::registrationRequired).toList();
        Set<LegalDocumentType> types=documents.stream().map(Document::type).collect(Collectors.toSet());
        if(!types.containsAll(Set.of(LegalDocumentType.USER_AGREEMENT,LegalDocumentType.PRIVACY_POLICY)))
            throw new LegalProblem(Kind.NOT_READY,"Account agreements are temporarily unavailable. Please try again later.");
        return documents;
    }
    public List<Long> requiredRevisionIds() {
        var ids=revisions.currentAccountRevisionIds();
        if(ids.size()!=2)throw new LegalProblem(Kind.NOT_READY,"Account agreements are temporarily unavailable. Please try again later.");
        return ids;
    }
    public Document revision(long id,String locale) {
        String requested=ContentLocale.require(locale).code();
        var row=revisions.find(id).filter(r->r.publishedAt()!=null).orElseThrow(()->new LegalProblem(Kind.MISSING,"The published document revision is unavailable."));
        var translation=translations.findFor(List.of(id)).stream().map(TranslationRow::translation).filter(t->t.locale().equals(requested)).findFirst()
                .orElseThrow(()->new LegalProblem(Kind.MISSING,"The requested revision language is unavailable."));
        return new Document(id,row.type(),row.type().slug(),row.revisionLabel(),translation.locale(),translation.title(),renderer.render(List.of(translation.bodyHtml())).get(translation.bodyHtml()),row.type().registrationRequired(),row.publishedAt());
    }
}
