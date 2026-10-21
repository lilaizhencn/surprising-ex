package com.surprising.gateway.provider.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.repository.KycDocumentRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.KycDocumentService;
import com.surprising.gateway.provider.storage.KycDocumentStorageService;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class UserKycUploadBoundaryTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"pdf,application/pdf", "jpg,image/jpeg", "png,image/png"})
    void actualMultipartStoresDetectedBytesForAuthenticatedUser(String extension, String mime) throws Exception {
        var properties = new GatewayProperties();
        properties.getKycDocuments().setEnabled(true);
        properties.getKycDocuments().setType("filesystem");
        properties.getKycDocuments().setRootPath(root.toString());
        var repository = mock(KycDocumentRepository.class);
        when(repository.insert(anyLong(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),any()))
                .thenAnswer(call -> new KycDocument(10L, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5),
                        "UPLOADED", call.getArgument(7), null));
        var auth = auth();
        var service = new KycDocumentService(repository,new KycDocumentStorageService(properties),properties,new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new UserComplianceController(mock(ComplianceService.class),auth,service,properties)).build();
        byte[] content = switch(extension) {
            case "pdf" -> "%PDF-1.7".getBytes();
            case "jpg" -> new byte[] {(byte)255,(byte)216,(byte)255,1};
            default -> new byte[] {(byte)137,'P','N','G',13,10,26,10,1};
        };
        mvc.perform(multipart("/api/v1/compliance/kyc/documents")
                .file(new MockMultipartFile("file","../../document."+extension,"application/octet-stream",content))
                .param("documentType"," passport ").header("Authorization","Bearer user").header("X-User-Id","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("7"))
                .andExpect(jsonPath("$.contentType").value(mime)).andExpect(jsonPath("$.originalFilename").value("document."+extension));
        try(var paths=Files.walk(root)) {
            var file=paths.filter(Files::isRegularFile).findFirst().orElseThrow();
            assertThat(Files.readAllBytes(file)).isEqualTo(content);
            assertThat(file.toString()).contains("/7/");
        }
    }

    @ParameterizedTest
    @CsvSource({"UNKNOWN,application/pdf,1000", "PASSPORT,image/png,1000", "PASSPORT,application/pdf,4"})
    void malformedUploadsNeverReachBusiness(String type,String mime,long max) throws Exception {
        var properties=new GatewayProperties();properties.getKycDocuments().setMaxFileSizeBytes(max);
        var documents=mock(KycDocumentService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new UserComplianceController(mock(ComplianceService.class),auth(),documents,properties)).build();
        mvc.perform(multipart("/api/v1/compliance/kyc/documents")
                .file(new MockMultipartFile("file","document.pdf",mime,"%PDF-1.7".getBytes()))
                .param("documentType",type).header("Authorization","Bearer user"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(documents);
    }

    @Test
    void failedAuthenticationPrecedesFileRead() throws Exception {
        var auth=mock(AuthService.class);when(auth.authenticateBearer("bad")).thenThrow(new IllegalArgumentException("invalid token"));
        var file=mock(org.springframework.web.multipart.MultipartFile.class);var documents=mock(KycDocumentService.class);
        var controller=new UserComplianceController(mock(ComplianceService.class),auth,documents,new GatewayProperties());
        assertThatThrownBy(()->controller.uploadKycDocument("bad","PASSPORT",file)).hasMessageContaining("invalid token");
        verifyNoInteractions(file,documents);
    }

    @Test
    void failedDatabaseWriteRemovesStoredObjectAndRetainsOriginalFailure() throws Exception {
        var properties=new GatewayProperties();var repository=mock(KycDocumentRepository.class);
        var storage=mock(KycDocumentStorageService.class);var failure=new IllegalStateException("database down");
        when(repository.insert(anyLong(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),any()))
                .thenThrow(failure);
        var service=new KycDocumentService(repository,storage,properties,new ObjectMapper());
        byte[] bytes="%PDF-1.7".getBytes();
        var upload=new com.surprising.gateway.provider.model.KycDocumentUpload("PASSPORT",bytes,"application/pdf","document.pdf",".pdf");
        assertThatThrownBy(()->service.upload(7L,upload)).isSameAs(failure);
        var order=inOrder(storage,repository);
        order.verify(storage).store(anyString(),eq(bytes),eq("application/pdf"));
        order.verify(repository).insert(anyLong(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),any());
        order.verify(storage).delete(anyString());
    }

    private AuthService auth() {
        var auth=mock(AuthService.class);when(auth.authenticateBearer("Bearer user")).thenReturn(
                new JwtPrincipal(7L,"user","NORMAL",List.of(),Instant.now().plusSeconds(60)));
        return auth;
    }
}
