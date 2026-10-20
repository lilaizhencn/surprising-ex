package com.surprising.gateway.c2c;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 交易系统自己的存储配置，不读取或依赖 social 的配置中心。密钥只从运行环境注入。 */
@Getter @Setter
@ConfigurationProperties(prefix="surprising.c2c.r2")
public class C2cR2Properties {
    private boolean enabled;
    private String type="s3";
    private String endpoint="";
    private String accountId="";
    private String accessKeyId="";
    private String secretAccessKey="";
    private String bucket="app-private";
    private String region="us-east-1";
    private String prefix="private/trading/c2c/";
    private int presignedUrlExpirySeconds=300;
    private String rootPath="/tmp/surprising-c2c-attachments";
}
