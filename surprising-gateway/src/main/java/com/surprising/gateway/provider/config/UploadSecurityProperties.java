package com.surprising.gateway.provider.config;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("surprising.gateway.upload-security")
@Validated
@Getter
@Setter
public class UploadSecurityProperties {
    private boolean scanEnabled;
    @NotBlank private String scannerHost = "127.0.0.1";
    @Min(1) @Max(65535) private int scannerPort = 3310;
    @Min(100) @Max(30000) private int timeoutMillis = 5000;
    @Min(1) @Max(32) private int concurrentScans = 2;
}
