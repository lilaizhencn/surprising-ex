package com.surprising.gateway.provider.legal.service;

import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.content.model.ContentLocale;
import com.surprising.gateway.provider.content.service.ContentMediaService;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.exception.LegalProblem.Kind;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import com.surprising.gateway.provider.legal.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Drafts are editable. Publishing replaces the current pointer while preserving every accepted text. */
@Service
public class LegalCmsService {
    private final LegalRevisionRepository revisions;
    private final LegalTranslationRepository translations;
    private final LegalAuditRepository audit;
    private final ContentMediaService media;
    private final AuthService auth;
    private final AdminApprovalService approvals;
    public LegalCmsService(LegalRevisionRepository revisions,LegalTranslationRepository translations,LegalAuditRepository audit,
                           ContentMediaService media,AuthService auth,AdminApprovalService approvals) {
        this.revisions=revisions;this.translations=translations;this.audit=audit;this.media=media;this.auth=auth;this.approvals=approvals;
    }
    public AdminPage list(String authorization,int offset,int limit) {
        admin(authorization,"admin.legal.read");
        var rows=revisions.list(offset,limit+1);
        return new AdminPage(rows.stream().limit(limit).toList(),offset,limit,rows.size()>limit);
    }
    public Revision get(String authorization,long id) { admin(authorization,"admin.legal.read");return revision(id); }
    public List<Audit> audit(String authorization,long id) { admin(authorization,"admin.legal.read");row(id);return audit.list(id); }

    @Transactional
    public Revision create(String authorization,Content content) {
        var actor=admin(authorization,"admin.legal.write");revisions.lockType(content.type());
        validateMedia(content.translations());
        var now=Instant.now();
        final long id;
        try { id=revisions.create(content,actor.userId(),now); }
        catch(DuplicateKeyException duplicate) { throw new LegalProblem(Kind.CONFLICT,"This document revision label already exists. Choose a new revision label."); }
        translations.replace(id,content.translations());
        media.retainArticleMedia("LEGAL",id,content.translations().stream().map(Translation::bodyHtml).toList());
        return audited(id,"CREATE",actor,null,now);
    }
    @Transactional
    public Revision update(String authorization,long id,long version,Content content) {
        var actor=admin(authorization,"admin.legal.write");
        revisions.lockType(row(id).type());var current=row(id);
        if(!current.type().equals(content.type()))throw new LegalProblem(Kind.INVALID,"The document type cannot change within a revision.");
        requireDraft(current,version);validateMedia(content.translations());var now=Instant.now();
        try {
            if(!revisions.update(id,version,content,actor.userId(),now))throw changed();
        } catch(DuplicateKeyException duplicate) { throw new LegalProblem(Kind.CONFLICT,"This document revision label already exists. Choose a new revision label."); }
        translations.replace(id,content.translations());
        media.retainArticleMedia("LEGAL",id,content.translations().stream().map(Translation::bodyHtml).toList());
        return audited(id,"UPDATE",actor,null,now);
    }
    @Transactional
    public Revision publish(String authorization,long id,long version,String reason,AdminRequestMetadata metadata,byte[] rawBody) {
        var actor=approvals.requireWrite(authorization,"admin.legal.publish","gateway-admin",metadata,rawBody);
        revisions.lockType(row(id).type());var current=row(id);requireDraft(current,version);
        var content=revision(id);
        Set<String> locales=content.translations().stream().map(Translation::locale).collect(Collectors.toSet());
        if(!locales.containsAll(ContentLocale.supported().stream().map(ContentLocale::code).toList()))
            throw new LegalProblem(Kind.INVALID,"Complete all twelve legal translations before publishing.");
        validateMedia(content.translations());
        var previous=revisions.current().stream().filter(r->r.type().equals(current.type())).findFirst();
        var now=Instant.now();revisions.supersedeCurrent(current.type(),actor.userId(),now);
        if(!revisions.publish(id,version,actor.userId(),now))throw changed();
        previous.ifPresent(old->audited(old.revisionId(),"SUPERSEDE",actor,"Replaced by revision "+id,now));
        return audited(id,"PUBLISH",actor,reason,now);
    }
    @Transactional
    public Revision withdraw(String authorization,long id,long version,String reason,AdminRequestMetadata metadata,byte[] rawBody) {
        var actor=approvals.requireWrite(authorization,"admin.legal.publish","gateway-admin",metadata,rawBody);
        revisions.lockType(row(id).type());var current=row(id);
        if(current.version()!=version)throw changed();
        if(current.status().equals("PUBLISHED") && current.type().registrationRequired())
            throw new LegalProblem(Kind.INVALID,"Publish a replacement before retiring an account agreement.");
        var now=Instant.now();
        if(!revisions.withdraw(id,version,actor.userId(),now))throw changed();
        return audited(id,"WITHDRAW",actor,reason,now);
    }
    private Revision audited(long id,String action,JwtPrincipal actor,String reason,Instant now) {
        var result=revision(id);audit.append(result,action,actor.userId(),reason,now);return result;
    }
    private void validateMedia(List<Translation> values) { values.forEach(t->media.requireArticleMedia(t.bodyHtml())); }
    private Revision revision(long id) {
        var row=row(id);
        return new Revision(id,row.type(),row.revisionLabel(),row.status(),row.version(),translations.findFor(List.of(id)).stream().map(TranslationRow::translation).toList(),row.createdAt(),row.updatedAt(),row.publishedAt());
    }
    private RevisionRow row(long id) { return revisions.find(id).orElseThrow(()->new LegalProblem(Kind.MISSING,"The document revision is unavailable.")); }
    private void requireDraft(RevisionRow row,long version) {
        if(row.version()!=version)throw changed();
        if(!row.status().equals("DRAFT"))throw new LegalProblem(Kind.CONFLICT,"Published legal text is immutable. Create a new draft revision.");
    }
    private LegalProblem changed() { return new LegalProblem(Kind.CONFLICT,"The document revision changed. Reload before trying again."); }
    private JwtPrincipal admin(String authorization,String permission) {
        try { return auth.requireAdminPermission(authorization,permission); }
        catch(IllegalArgumentException invalid) { throw new LegalProblem(Kind.AUTH_REQUIRED,"Sign in required."); }
        catch(IllegalStateException forbidden) { throw new LegalProblem(Kind.ACCESS_DENIED,"You do not have permission to manage legal documents."); }
    }
}
