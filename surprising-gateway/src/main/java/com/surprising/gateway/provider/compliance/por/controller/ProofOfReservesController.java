package com.surprising.gateway.provider.compliance.por.controller;

import com.surprising.gateway.provider.compliance.por.model.PorModels.*;
import com.surprising.gateway.provider.compliance.por.service.ProofOfReservesService;
import com.surprising.gateway.provider.auth.model.CurrentUser;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/compliance/por")
public class ProofOfReservesController {

    private final ProofOfReservesService porService;

    public ProofOfReservesController(ProofOfReservesService porService) {
        this.porService = porService;
    }

    @GetMapping("/latest")
    public PorSnapshotSummary latest() {
        return porService.getLatestSummary();
    }

    @GetMapping("/my-proof")
    public UserPorProofResponse myProof(@CurrentUser long userId) {
        try {
            return porService.getUserProof(userId);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/verify")
    public VerifyPorProofResponse verify(@Valid @RequestBody VerifyPorProofRequest request) {
        return porService.verifyProof(request);
    }
}
