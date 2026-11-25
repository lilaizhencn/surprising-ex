package com.surprising.gateway.provider.interceptor;

import com.surprising.gateway.provider.auth.support.ApiKeyRequestBinding;
import com.surprising.gateway.provider.auth.exception.ApiKeyAccessBlockedException;
import com.surprising.gateway.provider.auth.model.AdminOperationRecord;
import com.surprising.gateway.provider.auth.model.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.exception.AdminApprovalRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared authentication, permission, two-person approval, audit logging, and byte-scan helpers
 * used across Gateway controllers and filters.
 */
public final class GatewayAccessSupport {
    private static final Pattern ADMIN_SERVICE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final ThreadLocal<MessageDigest> SHA_256 =
            ThreadLocal.withInitial(com.surprising.gateway.provider.support.Sha256Digests::newDigest);

    public record GatewayIdentity(String userId, String username, String status, List<String> roles, boolean admin) {
        public static GatewayIdentity user(String userId) {
            return userId == null ? null : new GatewayIdentity(userId, null, null, List.of(), false);
        }
    }

    private GatewayAccessSupport() {
    }

    public static GatewayIdentity authenticateApiKey(
            GatewayApiKeyService apiKeys,
            ApiKeyRequestBinding apiKeyRequests,
            HttpServletRequest request,
            byte[] body,
            String requiredScope) {
        try {
            long user = apiKeys.authenticate(apiKeyRequests.bindNative(request, body), requiredScope);
            return GatewayIdentity.user(Long.toString(user));
        } catch (ApiKeyAccessBlockedException blocked) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, blocked.getMessage(), blocked);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, invalid.getMessage(), invalid);
        }
    }

    public static JwtPrincipal authenticateBearerPrincipal(AuthService authService, String authorization) {
        if (authService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "user auth service is not configured");
        }
        try {
            return resolveBearerPrincipal(authService, authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    public static GatewayIdentity authenticateBearerUser(AuthService authService, String authorization) {
        JwtPrincipal principal = authenticateBearerPrincipal(authService, authorization);
        return new GatewayIdentity(Long.toString(principal.operatingUserId()), principal.username(),
                principal.status(), principal.roles(), false);
    }

    public static GatewayIdentity authenticateAdmin(
            AuthService authService,
            GatewayProperties properties,
            HttpServletRequest request,
            String missingAuthMessage) {
        if (authService == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, missingAuthMessage);
        }
        try {
            JwtPrincipal principal = resolveBearerPrincipal(authService, request.getHeader(HttpHeaders.AUTHORIZATION));
            if (principal.roles().stream().noneMatch(properties.getSecurity().getAdminRoles()::contains)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
            }
            return new GatewayIdentity(Long.toString(principal.userId()), principal.username(),
                    principal.status(), principal.roles(), true);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private static JwtPrincipal resolveBearerPrincipal(AuthService authService, String authorization) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof JwtPrincipal principal
                && (authorization == null || authorization.equals(auth.getCredentials()))) {
            return principal;
        }
        return authService.authenticateBearer(authorization);
    }

    public static void enforceAdminPermission(
            AuthService authService,
            String service,
            boolean readMethod,
            GatewayIdentity identity) {
        if (identity == null || !identity.admin()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity required");
        }
        String normalizedService = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        if (!ADMIN_SERVICE_PATTERN.matcher(normalizedService).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid admin gateway service");
        }
        String permission = "admin.gateway." + normalizedService + "." + (readMethod ? "read" : "write");
        try {
            authService.requireAdminPermission(Long.parseLong(identity.userId()), identity.roles(), permission);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    public static void consumeAdminApproval(
            AdminApprovalService adminOperations,
            GatewayProperties properties,
            String service,
            String method,
            HttpServletRequest request,
            String bodyHash,
            GatewayIdentity identity) {
        if (identity == null || !identity.admin()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity required");
        }
        if (adminOperations == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval repository is not available");
        }
        String approvalHeader = request.getHeader(properties.getSecurity().getAdminApprovalHeader());
        if (approvalHeader == null || approvalHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval required");
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(approvalHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "invalid admin approval id", ex);
        }
        try {
            adminOperations.consumeGatewayApproval(
                    approvalId,
                    Long.parseLong(identity.userId()),
                    service,
                    method == null ? "GET" : method,
                    request.getRequestURI(),
                    request.getQueryString(),
                    bodyHash,
                    Instant.now());
        } catch (AdminApprovalRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    public static void recordAdminOperation(
            AdminApprovalService adminOperations,
            GatewayIdentity identity,
            String service,
            String method,
            HttpServletRequest request,
            String target,
            String bodyHash,
            Integer responseStatus,
            Long durationMs,
            boolean success,
            String errorMessage,
            String clientIp) {
        if (adminOperations == null || identity == null || !identity.admin()) {
            return;
        }
        adminOperations.recordGatewayOperation(new AdminOperationRecord(
                parseLong(identity.userId()),
                identity.username(),
                identity.roles(),
                service == null ? "unknown" : service,
                method == null ? "GET" : method,
                request.getRequestURI(),
                request.getQueryString(),
                target,
                bodyHash,
                responseStatus,
                durationMs,
                success,
                errorMessage,
                request.getHeader(HttpHeaders.USER_AGENT),
                clientIp,
                Instant.now()));
    }

    public static boolean isReadMethod(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method) || HttpMethod.OPTIONS.equals(method);
    }

    public static boolean isReadMethod(String method) {
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method);
    }

    public static String bodySha256(byte[] body, boolean hashEmptyWhenNull) {
        if ((body == null || body.length == 0) && !hashEmptyWhenNull) {
            return null;
        }
        MessageDigest md = SHA_256.get();
        md.reset();
        byte[] digest = md.digest(body == null ? EMPTY_BYTES : body);
        return HexFormat.of().formatHex(digest);
    }

    public static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static boolean containsAscii(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        byte first = needle[0];
        int max = haystack.length - needle.length;
        for (int i = 0; i <= max; i++) {
            if (haystack[i] != first) {
                continue;
            }
            boolean match = true;
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
