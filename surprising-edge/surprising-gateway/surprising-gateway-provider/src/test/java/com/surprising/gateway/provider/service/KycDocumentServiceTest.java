package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.KycDocumentRepository;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

class KycDocumentServiceTest {

    @Test
    void storesPdfAfterCheckingTheFileSignature(@TempDir Path tempDir) throws Exception {
        GatewayProperties properties = filesystemProperties(tempDir);
        KycDocumentRepository repository = mock(KycDocumentRepository.class);
        KycDocumentStorageService storage = new KycDocumentStorageService(properties);
        KycDocumentService service = new KycDocumentService(repository, storage, properties, new ObjectMapper());
        byte[] pdf = "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        when(repository.insert(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(new KycDocument(10L, 7L, "PASSPORT", "passport.pdf", "application/pdf",
                        pdf.length, sha256(pdf), "UPLOADED", Instant.now(), null));

        KycDocument document = service.upload(7L, "PASSPORT",
                new MockMultipartFile("file", "passport.pdf", "application/pdf", pdf));

        assertThat(document.documentType()).isEqualTo("PASSPORT");
        try (java.util.stream.Stream<Path> files = Files.walk(tempDir)) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
        verify(repository).insert(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void rejectsContentTypeSpoofingBeforeWriting(@TempDir Path tempDir) {
        GatewayProperties properties = filesystemProperties(tempDir);
        KycDocumentRepository repository = mock(KycDocumentRepository.class);
        KycDocumentService service = new KycDocumentService(
                repository, new KycDocumentStorageService(properties), properties, new ObjectMapper());

        assertThatThrownBy(() -> service.upload(7L, "PASSPORT",
                new MockMultipartFile("file", "passport.png", "image/png", "%PDF-1.7".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content type");

        verify(repository, never()).insert(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void requiresOwnedDocumentsAndMatchesRequestedType() {
        GatewayProperties properties = new GatewayProperties();
        KycDocumentRepository repository = mock(KycDocumentRepository.class);
        KycDocumentService service = new KycDocumentService(
                repository, mock(KycDocumentStorageService.class), properties, new ObjectMapper());
        KycDocument document = new KycDocument(10L, 7L, "PASSPORT", "passport.pdf", "application/pdf",
                8L, "a".repeat(64), "UPLOADED", Instant.now(), null);
        when(repository.findOwnedForSubmission(7L, List.of(10L))).thenReturn(List.of(document));

        assertThat(service.requireSubmissionDocuments(7L, List.of(10L), "PASSPORT"))
                .containsExactly(document);
        assertThatThrownBy(() -> service.requireSubmissionDocuments(7L, List.of(10L), "ID_CARD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void storageRemainsUnavailableUntilExplicitlyEnabled() {
        GatewayProperties properties = new GatewayProperties();
        KycDocumentStorageService storage = new KycDocumentStorageService(properties);

        assertThatThrownBy(() -> storage.store("kyc/7/test.pdf", "%PDF-1".getBytes(), "application/pdf"))
                .isInstanceOf(KycDocumentStorageUnavailableException.class);
    }

    private GatewayProperties filesystemProperties(Path root) {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.KycDocuments documents = new GatewayProperties.KycDocuments();
        documents.setEnabled(true);
        documents.setType("filesystem");
        documents.setRootPath(root.toString());
        properties.setKycDocuments(documents);
        return properties;
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }
}
