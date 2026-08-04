package com.surprising.gateway.provider.config;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@Configuration
public class GatewayHttpConfiguration {

    @Bean
    @Primary
    public RestTemplate gatewayRestTemplate(GatewayProperties properties) {
        return restTemplate(properties.getHttpClient().getConnectTimeout(),
                properties.getHttpClient().getReadTimeout());
    }

    @Bean
    @Qualifier("custodyWalletRestTemplate")
    public RestTemplate custodyWalletRestTemplate(GatewayProperties properties) {
        return restTemplate(properties.getHttpClient().getConnectTimeout(),
                properties.getCustodyWallet().getRequestTimeout());
    }

    private RestTemplate restTemplate(java.time.Duration connectTimeout, java.time.Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        return restTemplate;
    }
}
