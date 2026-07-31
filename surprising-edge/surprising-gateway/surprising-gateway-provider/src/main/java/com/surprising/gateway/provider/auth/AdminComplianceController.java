package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseCreateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseStatusUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.ComplianceUserSummary;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.KycUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTagCreateRequest;
import com.surprising.gateway.provider.config.GatewayTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/compliance")
public class AdminComplianceController {

    private final ComplianceService complianceService;
    private final ObjectMapper objectMapper;

    public AdminComplianceController(ComplianceService complianceService,
                                     ObjectMapper objectMapper) {
        this.complianceService = complianceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/users")
    public ComplianceUserQueryResponse users(@RequestHeader("Authorization") String authorization,
                                             @RequestParam(value = "userId", required = false) Long userId,
                                             @RequestParam(value = "kycStatus", required = false) String kycStatus,
                                             @RequestParam(value = "tagCode", required = false) String tagCode,
                                             @RequestParam(value = "limit", defaultValue = "100") int limit,
                                             @RequestParam(value = "cursor", required = false) String cursor,
                                             @RequestParam(value = "sort", required = false) String sort) {
        try {
            AdminCursorPage.CursorPage<ComplianceUserSummary> page =
                    complianceService.adminUsersPage(
                            authorization, userId, kycStatus, tagCode, limit, cursor, sort);
            return new ComplianceUserQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/users/{userId}")
    public ComplianceUserDetailResponse user(@RequestHeader("Authorization") String authorization,
                                             @PathVariable("userId") long userId) {
        try {
            ComplianceService.AdminComplianceUserDetail detail = complianceService.adminUser(authorization, userId);
            return new ComplianceUserDetailResponse(
                    detail.user(),
                    detail.kyc(),
                    detail.riskTags(),
                    detail.amlCases());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/risk-tags")
    public RiskTagQueryResponse riskTags(@RequestHeader("Authorization") String authorization,
                                         @RequestParam(value = "userId", required = false) Long userId,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "limit", defaultValue = "100") int limit,
                                         @RequestParam(value = "cursor", required = false) String cursor,
                                         @RequestParam(value = "sort", required = false) String sort) {
        try {
            AdminCursorPage.CursorPage<RiskTag> page =
                    complianceService.adminRiskTagsPage(
                            authorization, userId, status, limit, cursor, sort);
            return new RiskTagQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/aml-cases")
    public AmlCaseQueryResponse amlCases(@RequestHeader("Authorization") String authorization,
                                         @RequestParam(value = "userId", required = false) Long userId,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "limit", defaultValue = "100") int limit,
                                         @RequestParam(value = "cursor", required = false) String cursor,
                                         @RequestParam(value = "sort", required = false) String sort) {
        try {
            AdminCursorPage.CursorPage<AmlCase> page =
                    complianceService.adminAmlCasesPage(
                            authorization, userId, status, limit, cursor, sort);
            return new AmlCaseQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/users/{userId}/kyc")
    public KycProfile updateKyc(@RequestHeader("Authorization") String authorization,
                                @PathVariable("userId") long userId,
                                @RequestBody byte[] body,
                                HttpServletRequest httpRequest) {
        try {
            KycUpdateRequest request = readBody(body, KycUpdateRequest.class);
            return withApproval(() -> complianceService.adminUpsertKyc(
                    authorization, userId, request, requestMetadata(httpRequest), body));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/users/{userId}/risk-tags")
    public RiskTag createRiskTag(@RequestHeader("Authorization") String authorization,
                                 @PathVariable("userId") long userId,
                                 @RequestBody byte[] body,
                                 HttpServletRequest httpRequest) {
        try {
            RiskTagCreateRequest request = readBody(body, RiskTagCreateRequest.class);
            return withApproval(() -> complianceService.adminCreateRiskTag(
                    authorization, userId, request, requestMetadata(httpRequest), body));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/risk-tags/{tagId}/resolve")
    public RiskTag resolveRiskTag(@RequestHeader("Authorization") String authorization,
                                  @PathVariable("tagId") long tagId,
                                  @RequestBody(required = false) byte[] body,
                                  HttpServletRequest httpRequest) {
        try {
            return withApproval(() -> complianceService.adminResolveRiskTag(
                    authorization, tagId, requestMetadata(httpRequest), body));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/users/{userId}/aml-cases")
    public AmlCase createAmlCase(@RequestHeader("Authorization") String authorization,
                                 @PathVariable("userId") long userId,
                                 @RequestBody byte[] body,
                                 HttpServletRequest httpRequest) {
        try {
            AmlCaseCreateRequest request = readBody(body, AmlCaseCreateRequest.class);
            return withApproval(() -> complianceService.adminCreateAmlCase(
                    authorization, userId, request, requestMetadata(httpRequest), body));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/aml-cases/{caseId}/status")
    public AmlCase updateAmlCaseStatus(@RequestHeader("Authorization") String authorization,
                                       @PathVariable("caseId") long caseId,
                                       @RequestBody byte[] body,
                                       HttpServletRequest httpRequest) {
        try {
            AmlCaseStatusUpdateRequest request = readBody(body, AmlCaseStatusUpdateRequest.class);
            return withApproval(() -> complianceService.adminUpdateAmlCaseStatus(
                    authorization, caseId, request, requestMetadata(httpRequest), body));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private <T> T withApproval(Supplier<T> action) {
        try {
            return action.get();
        } catch (AdminApprovalService.AdminApprovalRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    private AdminApprovalService.AdminRequestMetadata requestMetadata(HttpServletRequest request) {
        return new AdminApprovalService.AdminRequestMetadata(
                request.getHeader(complianceService.approvalHeaderName()),
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                traceId(request));
    }

    private <T> T readBody(byte[] body, Class<T> type) {
        try {
            return objectMapper.readValue(body == null ? new byte[0] : body, type);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid request body", ex);
        }
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayTraceFilter.TRACE_ID_ATTRIBUTE);
        if (value instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return request.getHeader(GatewayTraceFilter.TRACE_ID_HEADER);
    }

    public record ComplianceUserQueryResponse(int count,
                                              List<ComplianceUserSummary> users,
                                              String nextCursor,
                                              boolean hasMore,
                                              String sort,
                                              int limit) {

        public ComplianceUserQueryResponse(int count, List<ComplianceUserSummary> users) {
            this(count, users, null, false, null, count);
        }
    }

    public record ComplianceUserDetailResponse(
            AuthenticatedUser user,
            KycProfile kyc,
            List<RiskTag> riskTags,
            List<AmlCase> amlCases) {
    }

    public record RiskTagQueryResponse(int count,
                                       List<RiskTag> tags,
                                       String nextCursor,
                                       boolean hasMore,
                                       String sort,
                                       int limit) {
        public RiskTagQueryResponse(int count, List<RiskTag> tags) {
            this(count, tags, null, false, "createdAt.desc", count);
        }
    }

    public record AmlCaseQueryResponse(int count,
                                       List<AmlCase> cases,
                                       String nextCursor,
                                       boolean hasMore,
                                       String sort,
                                       int limit) {
        public AmlCaseQueryResponse(int count, List<AmlCase> cases) {
            this(count, cases, null, false, "updatedAt.desc", count);
        }
    }
}
