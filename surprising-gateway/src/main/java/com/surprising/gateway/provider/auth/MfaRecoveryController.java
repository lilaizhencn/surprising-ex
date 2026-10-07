package com.surprising.gateway.provider.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MfaRecoveryController {
    private final AuthService auth;
    private final MfaRecoveryService recovery;
    private final AdminAuditRepository audit;

    public MfaRecoveryController(AuthService auth, MfaRecoveryService recovery, AdminAuditRepository audit) {
        this.auth = auth; this.recovery = recovery; this.audit = audit;
    }

    @PostMapping("/api/v1/security/mfa/recovery/challenge")
    public MfaRecoveryService.RecoveryChallenge issue(
            @RequestHeader("Authorization") String bearer,
            @Valid @RequestBody RecoveryChallengeRequest request,
            HttpServletRequest servletRequest) {
        try {
            return recovery.issue(auth.authenticateBearer(bearer).userId(), request.currentPassword(),
                    servletRequest.getRemoteAddr(), Instant.now());
        } catch (IllegalArgumentException ex) { throw badRequest(ex); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex); }
    }

    @PostMapping("/api/v1/security/mfa/recovery")
    public MfaRecoveryRepository.RecoveryRequest submit(
            @RequestHeader("Authorization") String bearer,
            @Valid @RequestBody RecoverySubmitRequest request) {
        try {
            return recovery.submit(auth.authenticateBearer(bearer).userId(),
                    new MfaRecoveryService.RecoverySubmission(request.challengeId(), request.currentPassword(),
                            request.code(), request.reason()), Instant.now());
        } catch (IllegalArgumentException ex) { throw badRequest(ex); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex); }
    }

    @GetMapping("/api/v1/security/mfa/recovery")
    public MfaRecoveryRepository.RecoveryRequest status(@RequestHeader("Authorization") String bearer) {
        return recovery.status(auth.authenticateBearer(bearer).userId());
    }

    @GetMapping("/api/v1/admin/compliance/mfa-recovery")
    public List<MfaRecoveryRepository.RecoveryRequest> pending(
            @RequestHeader("Authorization") String bearer) {
        auth.requireAdminPermission(bearer, "admin.compliance.read");
        return recovery.pending();
    }

    @PostMapping("/api/v1/admin/compliance/mfa-recovery/{requestId}/decision")
    public MfaRecoveryRepository.RecoveryRequest decide(
            @RequestHeader("Authorization") String bearer,
            @PathVariable long requestId,
            @Valid @RequestBody RecoveryDecisionRequest request,
            HttpServletRequest servletRequest) {
        try {
            AuthModels.JwtPrincipal admin = auth.requireAdminPermission(bearer, "admin.compliance.write");
            var result = recovery.decide(requestId, admin.userId(), request.approved(), request.reason(), Instant.now());
            audit.record(new AdminAuditRepository.AdminOperationRecord(admin.userId(), admin.username(), admin.roles(),
                    "gateway", "POST", "/api/v1/admin/compliance/mfa-recovery/" + requestId + "/decision",
                    null, null, null, 200, null, true, null, null,
                    servletRequest.getHeader("User-Agent"), servletRequest.getRemoteAddr(), Instant.now()));
            return result;
        } catch (IllegalArgumentException ex) { throw badRequest(ex); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex); }
    }

    private ResponseStatusException badRequest(IllegalArgumentException ex) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }

    public record RecoveryChallengeRequest(@NotBlank @Size(max = 128) String currentPassword) {}
    public record RecoverySubmitRequest(long challengeId, @NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(min = 6, max = 6) String code,
                                        @NotBlank @Size(min = 8, max = 500) String reason) {}
    public record RecoveryDecisionRequest(boolean approved,
                                          @jakarta.validation.constraints.NotBlank @Size(min = 8, max = 500)
                                          String reason) {}
}
