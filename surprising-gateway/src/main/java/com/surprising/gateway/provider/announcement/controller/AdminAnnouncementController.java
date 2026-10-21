package com.surprising.gateway.provider.announcement.controller;

import com.surprising.gateway.provider.announcement.service.AnnouncementService;
import com.surprising.gateway.provider.announcement.model.AnnouncementModels;

import com.surprising.gateway.provider.announcement.model.AnnouncementModels.Announcement;
import com.surprising.gateway.provider.announcement.model.AnnouncementModels.AdminPage;
import com.surprising.gateway.provider.announcement.model.AnnouncementModels.AuditEntry;
import com.surprising.gateway.provider.announcement.model.AnnouncementModels.SaveRequest;
import com.surprising.gateway.provider.auth.AdminApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/announcements")
public class AdminAnnouncementController {

    private final AnnouncementService service;
    private final AdminApprovalService approvals;
    private final ObjectMapper json;

    public AdminAnnouncementController(AnnouncementService service, AdminApprovalService approvals, ObjectMapper json) {
        this.service = service;
        this.approvals = approvals;
        this.json = json;
    }

    @GetMapping
    public AdminPage list(@RequestHeader("Authorization") String authorization,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) String query,
                          @RequestParam(defaultValue = "0") int offset,
                          @RequestParam(defaultValue = "50") int limit) {
        AnnouncementParameters.page(offset,limit);
        return service.adminList(authorization, AnnouncementParameters.status(status), AnnouncementParameters.optionalText(query), offset, limit);
    }

    @GetMapping("/{announcementId}")
    public Announcement get(@RequestHeader("Authorization") String authorization,
                            @PathVariable long announcementId) {
        AnnouncementParameters.id(announcementId);
        return service.adminGet(authorization, announcementId);
    }

    @PostMapping
    public Announcement create(@RequestHeader("Authorization") String authorization,
                               @RequestBody SaveRequest request) {
        return service.create(authorization, AnnouncementParameters.content(request,false));
    }

    @PutMapping("/{announcementId}")
    public Announcement update(@RequestHeader("Authorization") String authorization,
                               @PathVariable long announcementId,
                               @RequestBody SaveRequest request) {
        AnnouncementParameters.id(announcementId);
        return service.update(authorization, announcementId, AnnouncementParameters.content(request,true),request.version());
    }

    @PostMapping("/{announcementId}/publish")
    public Announcement publish(@RequestHeader("Authorization") String authorization,
                                @PathVariable long announcementId,
                                @RequestBody byte[] body,
                                HttpServletRequest httpRequest) {
        try {
            AnnouncementParameters.id(announcementId);
            var request=readBody(body,AnnouncementModels.PublishRequest.class);
            if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"request body is required");
            AnnouncementParameters.version(request.version());
            return service.publish(authorization,announcementId,request.version(),AnnouncementParameters.reason(request.reason(),false),requestMetadata(httpRequest),body);
        } catch (AdminApprovalService.AdminApprovalRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    @PostMapping("/{announcementId}/withdraw")
    public Announcement withdraw(@RequestHeader("Authorization") String authorization,
                                 @PathVariable long announcementId,
                                 @RequestBody byte[] body,
                                 HttpServletRequest httpRequest) {
        try {
            AnnouncementParameters.id(announcementId);
            var request=readBody(body,AnnouncementModels.WithdrawRequest.class);
            if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"request body is required");
            AnnouncementParameters.version(request.version());
            return service.withdraw(authorization,announcementId,request.version(),AnnouncementParameters.reason(request.reason(),true),requestMetadata(httpRequest),body);
        } catch (AdminApprovalService.AdminApprovalRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    @GetMapping("/{announcementId}/audit")
    public List<AuditEntry> audit(@RequestHeader("Authorization") String authorization,
                                  @PathVariable long announcementId) {
        AnnouncementParameters.id(announcementId);
        return service.audit(authorization, announcementId);
    }

    private AdminApprovalService.AdminRequestMetadata requestMetadata(HttpServletRequest request) {

        return new AdminApprovalService.AdminRequestMetadata(
                request.getHeader(approvals.approvalHeaderName()), request.getMethod(),
                request.getRequestURI(), request.getQueryString());
    }

    private <T> T readBody(byte[] body, Class<T> type) {
        try {
            return json.readValue(body == null ? new byte[0] : body, type);
        } catch (JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid request body", ex);
        }
    }
}
