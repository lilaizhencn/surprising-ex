package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KycDocumentStorageServiceTest {
    @TempDir Path root;
    @Test void publishesACompleteFileAndNeverOverwritesAnExistingObject() throws Exception {
        var properties=new GatewayProperties();properties.getKycDocuments().setEnabled(true);
        properties.getKycDocuments().setType("filesystem");properties.getKycDocuments().setRootPath(root.toString());
        var storage=new KycDocumentStorageService(properties);byte[] original=new byte[2000000];java.util.Arrays.fill(original,(byte)7);
        storage.store("qa/document",original,"application/pdf");
        assertThat(storage.read("qa/document")).isEqualTo(original);
        assertThatThrownBy(()->storage.store("qa/document",new byte[]{2},"application/pdf")).isInstanceOf(KycDocumentStorageException.class);
        assertThat(storage.read("qa/document")).isEqualTo(original);
        try(var files=Files.walk(root)) {assertThat(files.filter(Files::isRegularFile).map(p->p.getFileName().toString()).toList()).containsExactly("document");}
        assertThatThrownBy(()->storage.store("../escape",original,"application/pdf")).isInstanceOf(IllegalArgumentException.class);
    }
}
