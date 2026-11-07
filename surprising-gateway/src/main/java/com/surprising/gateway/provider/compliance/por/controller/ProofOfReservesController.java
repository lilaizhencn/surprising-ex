package com.surprising.gateway.provider.compliance.por.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.compliance.por.model.PorModels.*;
import com.surprising.gateway.provider.compliance.por.service.ProofOfReservesService;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/compliance/por")
public class ProofOfReservesController {

    private final ProofOfReservesService porService;
    private final AuthService authService;

    public ProofOfReservesController(ProofOfReservesService porService, AuthService authService) {
        this.porService = porService;
        this.authService = authService;
    }

    @GetMapping("/latest")
    public PorSnapshotSummary latest() {
        return porService.getLatestSummary();
    }

    @GetMapping("/my-proof")
    public UserPorProofResponse myProof(@RequestHeader("Authorization") String authorization) {
        JwtPrincipal principal = principal(authorization);
        try {
            return porService.getUserProof(principal.userId());
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/verify")
    public VerifyPorProofResponse verify(@Valid @RequestBody VerifyPorProofRequest request) {
        return porService.verifyProof(request);
    }

    private JwtPrincipal principal(String authorization) {
        try {
            return authService.authenticateBearer(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
}
