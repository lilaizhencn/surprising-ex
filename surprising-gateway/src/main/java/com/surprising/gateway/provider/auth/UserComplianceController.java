package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.service.KycDocumentNotFoundException;
import com.surprising.gateway.provider.service.KycDocumentStorageException;
import com.surprising.gateway.provider.service.KycDocumentStorageUnavailableException;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/compliance")
public class UserComplianceController {

    private final ComplianceService complianceService;

    public UserComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @GetMapping("/kyc")
    public KycProfile kyc(@RequestHeader("Authorization") String authorization) {
        try {
            return complianceService.userKyc(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/kyc")
    public KycProfile submitKyc(@RequestHeader("Authorization") String authorization,
                                @Valid @RequestBody KycSubmissionRequest request) {
        try {
            return complianceService.submitUserKyc(authorization, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping(value = "/kyc/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KycDocument uploadKycDocument(@RequestHeader("Authorization") String authorization,
                                         @RequestParam("documentType") String documentType,
                                         @RequestPart("file") MultipartFile file) {
        try {
            return complianceService.uploadUserKycDocument(authorization, documentType, file);
        } catch (KycDocumentStorageUnavailableException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (KycDocumentStorageException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/kyc/documents")
    public List<KycDocument> kycDocuments(@RequestHeader("Authorization") String authorization) {
        try {
            return complianceService.userKycDocuments(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/kyc/documents/{documentId}")
    public ResponseEntity<byte[]> kycDocument(@RequestHeader("Authorization") String authorization,
                                              @PathVariable long documentId) {
        try {
            ComplianceService.KycDocumentContent content = complianceService.userKycDocument(authorization, documentId);
            return documentResponse(content);
        } catch (KycDocumentNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (KycDocumentStorageException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private ResponseEntity<byte[]> documentResponse(ComplianceService.KycDocumentContent content) {
        MediaType mediaType = MediaType.parseMediaType(content.document().contentType());
        String extension = MediaType.APPLICATION_PDF.includes(mediaType)
                ? ".pdf" : MediaType.IMAGE_PNG.includes(mediaType) ? ".png" : ".jpg";
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"kyc-document-" + content.document().documentId() + extension + "\"")
                .body(content.content());
    }
}
