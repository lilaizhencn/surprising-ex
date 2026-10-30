package com.surprising.gateway.provider.legal.controller;

import com.surprising.gateway.provider.auth.controller.AdminApprovalRequestBinding;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.exception.AdminApprovalRequiredException;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.exception.LegalProblem.Kind;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import com.surprising.gateway.provider.legal.service.LegalCmsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/legal/revisions")
public class AdminLegalController {
    private final LegalCmsService service;
    private final AdminApprovalService approvals;
    private final ObjectMapper json;
    public AdminLegalController(LegalCmsService service,AdminApprovalService approvals,ObjectMapper json) { this.service=service;this.approvals=approvals;this.json=json; }
    @GetMapping public AdminPage list(@RequestHeader("Authorization") String authorization,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) {
        LegalParameters.page(offset,limit);return service.list(authorization,offset,limit);
    }
    @GetMapping("/{revisionId}") public Revision get(@RequestHeader("Authorization") String authorization,@PathVariable long revisionId) {
        LegalParameters.id(revisionId);return service.get(authorization,revisionId);
    }
    @PostMapping public Revision create(@RequestHeader("Authorization") String authorization,@RequestBody SaveRequest request) {
        return service.create(authorization,LegalParameters.content(request));
    }
    @PutMapping("/{revisionId}") public Revision update(@RequestHeader("Authorization") String authorization,@PathVariable long revisionId,@RequestBody SaveRequest request) {
        LegalParameters.id(revisionId);var content=LegalParameters.content(request);LegalParameters.version(request.version());
        return service.update(authorization,revisionId,request.version(),content);
    }
    @PostMapping("/{revisionId}/publish") public Revision publish(@RequestHeader("Authorization") String authorization,@PathVariable long revisionId,@RequestBody byte[] body,HttpServletRequest http) {
        LegalParameters.id(revisionId);var request=command(body);LegalParameters.version(request.version());
        try { return service.publish(authorization,revisionId,request.version(),LegalParameters.reason(request.reason(),false),metadata(http),body); }
        catch(AdminApprovalRequiredException required) { throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,required.getMessage(),required); }
    }
    @PostMapping("/{revisionId}/withdraw") public Revision withdraw(@RequestHeader("Authorization") String authorization,@PathVariable long revisionId,@RequestBody byte[] body,HttpServletRequest http) {
        LegalParameters.id(revisionId);var request=command(body);LegalParameters.version(request.version());
        try { return service.withdraw(authorization,revisionId,request.version(),LegalParameters.reason(request.reason(),true),metadata(http),body); }
        catch(AdminApprovalRequiredException required) { throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,required.getMessage(),required); }
    }
    @GetMapping("/{revisionId}/audit") public List<Audit> audit(@RequestHeader("Authorization") String authorization,@PathVariable long revisionId) {
        LegalParameters.id(revisionId);return service.audit(authorization,revisionId);
    }
    private PublishRequest command(byte[] body) {
        try {
            var value=json.readValue(body,PublishRequest.class);
            if(value==null)throw new LegalProblem(Kind.INVALID,"A revision command is required.");
            return value;
        } catch(JacksonException invalid) { throw new LegalProblem(Kind.INVALID,"Invalid revision command."); }
    }
    private AdminRequestMetadata metadata(HttpServletRequest request) {
        return new AdminRequestMetadata(AdminApprovalRequestBinding.reference(request.getHeader(approvals.approvalHeaderName())),request.getMethod(),request.getRequestURI(),request.getQueryString());
    }
}
