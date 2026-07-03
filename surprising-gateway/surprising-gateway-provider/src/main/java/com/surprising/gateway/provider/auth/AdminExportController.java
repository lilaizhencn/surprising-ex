package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AdminExportRepository.AdminExportDownload;
import com.surprising.gateway.provider.auth.AuthModels.AdminExportCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminExportJobQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminExportJobResponse;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/admin/exports")
public class AdminExportController {

    private final AuthService authService;
    private final AdminExportRepository repository;
    private final AdminExportService exportService;
    private final AdminApprovalRepository approvalRepository;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public AdminExportController(AuthService authService,
                                 AdminExportRepository repository,
                                 AdminExportService exportService,
                                 AdminApprovalRepository approvalRepository,
                                 GatewayProperties properties,
                                 ObjectMapper objectMapper) {
        this.authService = authService;
        this.repository = repository;
        this.exportService = exportService;
        this.approvalRepository = approvalRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public AdminExportJobQueryResponse exports(@RequestHeader("Authorization") String authorization,
                                               @RequestParam(value = "status", required = false) String status,
                                               @RequestParam(value = "exportType", required = false) String exportType,
                                               @RequestParam(value = "limit", defaultValue = "100") int limit,
                                               @RequestParam(value = "cursor", required = false) String cursor,
                                               @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.requireAdminPermission(authorization, "admin.exports.read");
            var page = repository.jobPage(status, exportType, limit, cursor, sort);
            return new AdminExportJobQueryResponse(page.items().size(), page.items(),
                    page.nextCursor(), page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/{exportId}")
    public AdminExportJobResponse export(@RequestHeader("Authorization") String authorization,
                                         @PathVariable("exportId") long exportId) {
        try {
            authService.requireAdminPermission(authorization, "admin.exports.read");
            return repository.job(exportId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "export not found"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping
    public AdminExportJobResponse create(@RequestHeader("Authorization") String authorization,
                                         @RequestBody byte[] body,
                                         HttpServletRequest httpRequest) {
        try {
            AdminExportCreateRequest request = readBody(body, AdminExportCreateRequest.class);
            var principal = authService.requireAdminPermission(authorization, "admin.exports.write");
            requireLocalAdminApproval(principal.userId(), httpRequest, body);
            return exportService.create(principal, request, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/{exportId}/download")
    public ResponseEntity<byte[]> download(@RequestHeader("Authorization") String authorization,
                                           @PathVariable("exportId") long exportId) {
        try {
            authService.requireAdminPermission(authorization, "admin.exports.read");
            AdminExportDownload download = repository.download(exportId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "export file not found"));
            String fileName = safeFileName(download.fileName(), exportId);
            String contentType = download.contentType() == null || download.contentType().isBlank()
                    ? "text/csv; charset=utf-8"
                    : download.contentType();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(fileName).build().toString())
                    .body(download.content());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private <T> T readBody(byte[] body, Class<T> type) {
        try {
            return objectMapper.readValue(body == null ? new byte[0] : body, type);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid request body", ex);
        }
    }

    private void requireLocalAdminApproval(long principalUserId,
                                           HttpServletRequest request,
                                           byte[] body) {
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
            approvalRepository.consumeApproved(
                    approvalId,
                    principalUserId,
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

    private String safeFileName(String value, long exportId) {
        String fileName = value == null || value.isBlank() ? "admin-export-" + exportId + ".csv" : value.trim();
        return fileName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
