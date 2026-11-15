package com.surprising.gateway.provider.config;

import com.surprising.gateway.provider.auth.service.AuthService;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GatewayHttpConfiguration {

    @Bean
    public WebMvcConfigurer currentUserWebMvcConfigurer(ObjectProvider<AuthService> authServiceProvider) {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentUserArgumentResolver(authServiceProvider));
            }
        };
    }

    @Bean
    @Primary
    public HttpClient gatewayHttpClient(GatewayProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getHttpClient().getConnectTimeout())
                .build();
    }

    @Bean
    @Primary
    public RestTemplate gatewayRestTemplate(GatewayProperties properties, HttpClient gatewayHttpClient) {
        return restTemplate(gatewayHttpClient, properties.getHttpClient().getReadTimeout());
    }

    private static final tools.jackson.databind.json.JsonMapper CUSTODY_FALLBACK_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder()
                    .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build();

    public RestTemplate custodyWalletRestTemplate(GatewayProperties properties, HttpClient gatewayHttpClient) {
        return custodyWalletRestTemplate(properties, gatewayHttpClient, (ObjectProvider<tools.jackson.databind.ObjectMapper>) null);
    }

    @Bean
    @Qualifier("custodyWalletRestTemplate")
    public RestTemplate custodyWalletRestTemplate(
            GatewayProperties properties,
            HttpClient gatewayHttpClient,
            ObjectProvider<tools.jackson.databind.ObjectMapper> objectMapperProvider) {
        RestTemplate template = restTemplate(gatewayHttpClient, properties.getCustodyWallet().getRequestTimeout());
        tools.jackson.databind.ObjectMapper springMapper = objectMapperProvider == null ? null : objectMapperProvider.getIfAvailable();
        tools.jackson.databind.json.JsonMapper mapper =
                springMapper instanceof tools.jackson.databind.json.JsonMapper jsonMapper
                        && jsonMapper.isEnabled(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        ? jsonMapper
                        : CUSTODY_FALLBACK_MAPPER;
        template.getMessageConverters().replaceAll(converter ->
                converter instanceof org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
                        ? new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(mapper) : converter);
        return template;
    }

    private RestTemplate restTemplate(HttpClient httpClient, java.time.Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
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
