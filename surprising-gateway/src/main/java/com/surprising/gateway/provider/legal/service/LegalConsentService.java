package com.surprising.gateway.provider.legal.service;

import com.surprising.gateway.provider.content.model.ContentLocale;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.exception.LegalProblem.Kind;
import com.surprising.gateway.provider.legal.model.LegalDocumentType;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import com.surprising.gateway.provider.legal.repository.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegalConsentService {
    private final LegalDocumentService documents;
    private final LegalRevisionRepository revisions;
    private final LegalTranslationRepository translations;
    private final LegalConsentRepository consents;
    public LegalConsentService(LegalDocumentService documents,LegalRevisionRepository revisions,LegalTranslationRepository translations,LegalConsentRepository consents) {
        this.documents=documents;this.revisions=revisions;this.translations=translations;this.consents=consents;
    }
    public ConsentStatus status(long userId,String locale) {
        var required=documents.required(locale);
        var accepted=consents.findFor(userId,required.stream().map(Document::revisionId).toList()).stream().map(Consent::revisionId).collect(java.util.stream.Collectors.toSet());
        return new ConsentStatus(required,required.stream().map(Document::revisionId).filter(id->!accepted.contains(id)).toList());
    }
    /** Called before registration creates any user or referral side effects. */
    @Transactional
    public void validateRegistration(List<Acceptance> choices) {
        var normalized=validateAndLock(choices);
        var required=documents.requiredRevisionIds();
        Set<Long> ids=normalized.stream().map(Acceptance::revisionId).collect(java.util.stream.Collectors.toSet());
        if(required.stream().anyMatch(id->!ids.contains(id)))throw required();
    }
    /** Invoked in the same transaction as issuing an authenticated session, including MFA and OAuth. */
    @Transactional
    public void recordAndRequire(long userId,List<Acceptance> choices,Instant now) {
        var normalized=validateAndLock(choices);
        if(!normalized.isEmpty())consents.insert(userId,normalized,now);
        var requiredIds=documents.requiredRevisionIds();
        var accepted=consents.findFor(userId,requiredIds).stream().map(Consent::revisionId).collect(java.util.stream.Collectors.toSet());
        if(!accepted.containsAll(requiredIds))throw required();
    }
    @Transactional
    public ConsentStatus acknowledge(long userId,List<Acceptance> choices,String locale) {
        if(choices==null || choices.isEmpty())throw new LegalProblem(Kind.INVALID,"Select the documents you have reviewed.");
        var normalized=validateAndLock(choices);
        consents.insert(userId,normalized,Instant.now());
        return status(userId,locale);
    }
    private List<Acceptance> validateAndLock(List<Acceptance> choices) {
        if(choices==null)choices=List.of();
        if(choices.size()>LegalDocumentType.values().length)throw new LegalProblem(Kind.INVALID,"Too many legal acknowledgements.");
        var normalized=new ArrayList<Acceptance>();
        var ids=new HashSet<Long>();
        var types=new HashSet<>(Set.of(LegalDocumentType.USER_AGREEMENT,LegalDocumentType.PRIVACY_POLICY));
        for(var choice:choices) {
            if(choice==null || choice.revisionId()<1 || !ids.add(choice.revisionId()))throw new LegalProblem(Kind.INVALID,"Invalid or duplicate legal revision.");
            String locale;
            try { locale=ContentLocale.require(choice.locale()).code(); }
            catch(IllegalArgumentException invalid) { throw new LegalProblem(Kind.INVALID,"Unsupported legal language."); }
            if(choice.locale()==null)throw new LegalProblem(Kind.INVALID,"The language of the reviewed document is required.");
            var revision=revisions.find(choice.revisionId()).orElseThrow(()->new LegalProblem(Kind.CONFLICT,"The legal documents have changed. Please review the latest revision."));
            types.add(revision.type());normalized.add(new Acceptance(choice.revisionId(),locale));
        }
        types.stream().sorted(Comparator.comparing(LegalDocumentType::name)).forEach(revisions::lockType);
        // Publication uses the same type locks; no obsolete revision can be acknowledged during replacement.
        for(var choice:normalized) {
            var revision=revisions.find(choice.revisionId()).orElseThrow(()->new LegalProblem(Kind.CONFLICT,"The legal documents have changed."));
            if(!revision.status().equals("PUBLISHED"))throw new LegalProblem(Kind.CONFLICT,"The legal documents have changed. Please review the latest revision.");
            if(!translations.hasLocale(choice.revisionId(),choice.locale()))
                throw new LegalProblem(Kind.INVALID,"The reviewed language is unavailable for this revision.");
        }
        return List.copyOf(normalized);
    }
    private LegalProblem required() { return new LegalProblem(Kind.CONSENT_REQUIRED,"Please review and acknowledge the current User Agreement and Privacy Policy."); }
}
