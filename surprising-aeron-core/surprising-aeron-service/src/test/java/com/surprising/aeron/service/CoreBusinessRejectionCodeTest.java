package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.aeron.protocol.CoreResultCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CoreBusinessRejectionCodeTest {
    @Test
    void everyLiteralBusinessRejectionHasARegisteredWireCode() throws Exception {
        var pattern = Pattern.compile("new CoreStateRejectedException\\(\\s*\"([A-Z_]+)\"");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    String code = matcher.group(1);
                    assertThat(CoreResultCode.fromRejectionCode(code).name())
                            .as("Business rejection in %s must not lose its reason", file)
                            .isEqualTo(code);
                }
            }
        }
    }
}
