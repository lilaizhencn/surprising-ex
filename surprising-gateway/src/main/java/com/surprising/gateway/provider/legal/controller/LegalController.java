package com.surprising.gateway.provider.legal.controller;

import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.exception.LegalProblem.Kind;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import com.surprising.gateway.provider.legal.service.LegalConsentService;
import com.surprising.gateway.provider.legal.service.LegalDocumentService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/legal")
public class LegalController {
    private final LegalDocumentService documents;
    private final LegalConsentService consents;
    private final AuthService auth;
    public LegalController(LegalDocumentService documents,LegalConsentService consents,AuthService auth) { this.documents=documents;this.consents=consents;this.auth=auth; }
    @GetMapping("/current") public List<Document> current(@RequestParam(defaultValue="en-US") String locale) { return documents.current(LegalParameters.locale(locale)); }
    @GetMapping("/documents/{slug}") public Document document(@PathVariable String slug,@RequestParam(defaultValue="en-US") String locale) {
        return documents.current(LegalParameters.locale(locale)).stream().filter(d->d.slug().equals(slug)).findFirst().orElseThrow(()->new LegalProblem(Kind.MISSING,"The document is unavailable."));
    }
    @GetMapping("/revisions/{revisionId}") public Document revision(@PathVariable long revisionId,@RequestParam(defaultValue="en-US") String locale) {
        LegalParameters.id(revisionId);return documents.revision(revisionId,LegalParameters.locale(locale));
    }
    @GetMapping("/status") public ConsentStatus status(@RequestHeader("Authorization") String authorization,@RequestParam(defaultValue="en-US") String locale) {
        return consents.status(user(authorization),LegalParameters.locale(locale));
    }
    @PostMapping("/consents") public ConsentStatus acknowledge(@RequestHeader("Authorization") String authorization,@RequestBody ConsentRequest request,@RequestParam(defaultValue="en-US") String locale) {
        if(request==null)throw new LegalProblem(Kind.INVALID,"Select the documents you reviewed.");
        return consents.acknowledge(user(authorization),request.documents(),LegalParameters.locale(locale));
    }
    private long user(String authorization) {
        try { return auth.authenticateBearer(authorization).userId(); }
        catch(IllegalArgumentException invalid) { throw new LegalProblem(Kind.AUTH_REQUIRED,"Sign in required."); }
        catch(IllegalStateException denied) { throw new LegalProblem(Kind.ACCESS_DENIED,"Your account cannot acknowledge documents at this time."); }
    }
}
