package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.ComplianceRepository.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceRepository.AmlCaseCreateRequest;
import com.surprising.gateway.provider.auth.ComplianceRepository.AmlCaseStatusUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceRepository.ComplianceUserSummary;
import com.surprising.gateway.provider.auth.ComplianceRepository.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceRepository.KycUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceRepository.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceRepository.RiskTagCreateRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
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

    private final AuthService authService;
    private final ComplianceRepository complianceRepository;
    private final AdminApprovalRepository adminApprovalRepository;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public AdminComplianceController(AuthService authService,
                                     ComplianceRepository complianceRepository,
                                     AdminApprovalRepository adminApprovalRepository,
                                     GatewayProperties properties,
                                     ObjectMapper objectMapper) {
        this.authService = authService;
        this.complianceRepository = complianceRepository;
        this.adminApprovalRepository = adminApprovalRepository;
        this.properties = properties;
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
            authService.requireAdminPermission(authorization, "admin.compliance.read");
            AdminCursorPage.CursorPage<ComplianceUserSummary> page =
                    complianceRepository.usersPage(userId, kycStatus, tagCode, limit, cursor, sort);
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
            authService.requireAdminPermission(authorization, "admin.compliance.read");
            AuthenticatedUser user = authService.adminUser(authorization, userId);
            return new ComplianceUserDetailResponse(
                    user,
                    complianceRepository.kyc(userId),
                    complianceRepository.riskTags(userId, null, 200),
                    complianceRepository.amlCases(userId, null, 200));
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
            authService.requireAdminPermission(authorization, "admin.compliance.read");
            AdminCursorPage.CursorPage<RiskTag> page =
                    complianceRepository.riskTagsPage(userId, status, limit, cursor, sort);
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
            authService.requireAdminPermission(authorization, "admin.compliance.read");
            AdminCursorPage.CursorPage<AmlCase> page =
                    complianceRepository.amlCasesPage(userId, status, limit, cursor, sort);
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
            var principal = requireWrite(authorization, httpRequest, body);
            authService.adminUser(authorization, userId);
            KycUpdateRequest request = readBody(body, KycUpdateRequest.class);
            return complianceRepository.upsertKyc(userId, principal.userId(), request, Instant.now());
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
            var principal = requireWrite(authorization, httpRequest, body);
            authService.adminUser(authorization, userId);
            RiskTagCreateRequest request = readBody(body, RiskTagCreateRequest.class);
            return complianceRepository.createRiskTag(userId, principal.userId(), request, Instant.now());
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
            var principal = requireWrite(authorization, httpRequest, body);
            return complianceRepository.resolveRiskTag(tagId, principal.userId(), Instant.now());
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
            var principal = requireWrite(authorization, httpRequest, body);
            authService.adminUser(authorization, userId);
            AmlCaseCreateRequest request = readBody(body, AmlCaseCreateRequest.class);
            return complianceRepository.createAmlCase(userId, principal.userId(), request, Instant.now());
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
            var principal = requireWrite(authorization, httpRequest, body);
            AmlCaseStatusUpdateRequest request = readBody(body, AmlCaseStatusUpdateRequest.class);
            return complianceRepository.updateAmlCaseStatus(caseId, principal.userId(), request, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private AuthModels.JwtPrincipal requireWrite(String authorization, HttpServletRequest request, byte[] body) {
        var principal = authService.requireAdminPermission(authorization, "admin.compliance.write");
        requireLocalAdminApproval(principal, request, body);
        return principal;
    }

    private void requireLocalAdminApproval(AuthModels.JwtPrincipal principal, HttpServletRequest request, byte[] body) {
        if (!properties.getSecurity().isRequireApprovalForHighRiskAdminWrites()) {
            return;
        }
        String approvalIdHeader = request.getHeader(properties.getSecurity().getAdminApprovalHeader());
        if (approvalIdHeader == null || approvalIdHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval required");
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(approvalIdHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "invalid admin approval id", ex);
        }
        try {
            adminApprovalRepository.consumeApproved(
                    approvalId,
                    principal.userId(),
                    "gateway-admin",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getQueryString(),
                    bodySha256(body),
                    traceId(request),
                    Instant.now());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    private <T> T readBody(byte[] body, Class<T> type) {
        try {
            return objectMapper.readValue(body == null ? new byte[0] : body, type);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid request body", ex);
        }
    }

    private String bodySha256(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
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
