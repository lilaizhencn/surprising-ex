package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.config.UploadSecurityProperties;
import com.surprising.gateway.provider.auth.repository.KycDocumentRepository;
import com.surprising.gateway.provider.storage.KycDocumentStorageService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.model.KycDocumentUpload;
import com.surprising.gateway.c2c.service.*;
import com.surprising.gateway.c2c.model.C2cAttachment;
import com.surprising.gateway.c2c.repository.*;
import com.surprising.gateway.c2c.storage.C2cR2Storage;
import com.surprising.gateway.c2c.utils.C2cDigests;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class UploadContentScannerTest {
    private UploadSecurityProperties settings(int port) {
        var settings = new UploadSecurityProperties(); settings.setScanEnabled(true);
        settings.setScannerPort(port); settings.setTimeoutMillis(300); return settings;
    }

    @Test void acceptsOnlyAnExplicitCleanReplyAndRejectsMalwareOrScannerErrors() throws Exception {
        for (String verdict : List.of("stream: OK", "stream: Eicar-Test-Signature FOUND", "INSTREAM size limit exceeded. ERROR", "UNKNOWN")) {
            try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 var workers = Executors.newVirtualThreadPerTaskExecutor();
                 var scanner = new UploadContentScanner(settings(server.getLocalPort()))) {
                byte[] bytes = "%PDF-1.7 test bytes".getBytes(StandardCharsets.US_ASCII);
                var peer = workers.submit(() -> {
                    try (var socket = server.accept()) {
                        var input = new DataInputStream(socket.getInputStream());
                        assertThat(new String(input.readNBytes(10), StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                        var content = new ByteArrayOutputStream();
                        for (int length; (length = input.readInt()) != 0;) content.write(input.readNBytes(length));
                        assertThat(content.toByteArray()).isEqualTo(bytes);
                        socket.getOutputStream().write((verdict + "\0").getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
                if (verdict.endsWith("OK")) assertThatCode(() -> scanner.scan(bytes)).doesNotThrowAnyException();
                else if (verdict.endsWith("FOUND")) assertThatThrownBy(() -> scanner.scan(bytes)).isInstanceOf(IllegalArgumentException.class);
                else assertThatThrownBy(() -> scanner.scan(bytes)).isInstanceOf(GatewayRequestLimits.Rejection.class);
                peer.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test void timeoutClosesBlockedWritesAndReleasesCapacity() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor();
             var scanner = new UploadContentScanner(settings(server.getLocalPort()))) {
            var peer = workers.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setReceiveBufferSize(1024);
                    // Hold the peer open without consuming the stream until the caller's total deadline closes it.
                    Thread.sleep(700);
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            assertThatThrownBy(() -> scanner.scan(new byte[15 * 1024 * 1024])).isInstanceOf(GatewayRequestLimits.Rejection.class);
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void unsafeKycDoesNotReachStorageAndUnsafePresignedC2cNeverBecomesReady() {
        var scanner = mock(UploadContentScanner.class); var limits = mock(GatewayRequestLimits.class);
        doThrow(new IllegalArgumentException("unsafe file content")).when(scanner).scan(any());
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        var documents = mock(KycDocumentRepository.class); var storage = mock(KycDocumentStorageService.class);
        var kyc = new KycDocumentService(documents, storage, new GatewayProperties(), new ObjectMapper(), limits, scanner);
        assertThatThrownBy(() -> kyc.upload(7, new KycDocumentUpload("PASSPORT", pdf, "application/pdf", "document.pdf", ".pdf")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage, documents);
        var attachments = mock(C2cAttachmentRepository.class); var objects = mock(C2cR2Storage.class);
        UUID id = UUID.randomUUID();
        when(attachments.find(id)).thenReturn(Optional.of(new C2cAttachment(id, 7, null, null, "PROOF", "private/key", "application/pdf", pdf.length, C2cDigests.sha256(pdf), "document.pdf", "PENDING")));
        when(objects.read("private/key")).thenReturn(pdf);
        var c2c = new C2cAttachments(attachments, mock(), mock(), mock(), mock(), mock(), objects, mock(PlatformTransactionManager.class), limits, scanner);
        assertThatThrownBy(() -> c2c.confirm(7, id)).isInstanceOf(IllegalArgumentException.class);
        verify(attachments, never()).markReady(any());
    }
}
