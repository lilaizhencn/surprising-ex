package com.surprising.gateway.provider.config;

import com.surprising.gateway.provider.auth.service.AuthService;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
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
    public RestClient gatewayRestClient(GatewayProperties properties, HttpClient gatewayHttpClient) {
        return restClient(gatewayHttpClient, properties.getHttpClient().getReadTimeout()).build();
    }

    private static final tools.jackson.databind.json.JsonMapper CUSTODY_FALLBACK_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder()
                    .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build();

    public RestClient.Builder custodyWalletRestClientBuilder(GatewayProperties properties, HttpClient gatewayHttpClient) {
        return custodyWalletRestClientBuilder(properties, gatewayHttpClient, (ObjectProvider<tools.jackson.databind.ObjectMapper>) null);
    }

    @Bean(name = "custodyWalletRestClient")
    @Qualifier("custodyWalletRestClient")
    public RestClient custodyWalletRestClient(
            GatewayProperties properties,
            HttpClient gatewayHttpClient,
            ObjectProvider<tools.jackson.databind.ObjectMapper> objectMapperProvider) {
        return custodyWalletRestClientBuilder(properties, gatewayHttpClient, objectMapperProvider).build();
    }

    public RestClient.Builder custodyWalletRestClientBuilder(
            GatewayProperties properties,
            HttpClient gatewayHttpClient,
            ObjectProvider<tools.jackson.databind.ObjectMapper> objectMapperProvider) {
        RestClient.Builder builder = restClient(gatewayHttpClient, properties.getCustodyWallet().getRequestTimeout());
        tools.jackson.databind.ObjectMapper springMapper = objectMapperProvider == null ? null : objectMapperProvider.getIfAvailable();
        tools.jackson.databind.json.JsonMapper mapper =
                springMapper instanceof tools.jackson.databind.json.JsonMapper jsonMapper
                        && jsonMapper.isEnabled(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        ? jsonMapper
                        : CUSTODY_FALLBACK_MAPPER;
        builder.configureMessageConverters(converters -> converters.withJsonConverter(
                new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(mapper)));
        return builder;
    }

    private RestClient.Builder restClient(HttpClient httpClient, java.time.Duration readTimeout) {
        return RestClient.builder()
                .requestFactory(requestFactory(httpClient, readTimeout))
                .defaultStatusHandler(status -> false, (request, response) -> {
                });
    }

    JdkClientHttpRequestFactory requestFactory(HttpClient httpClient, java.time.Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }
}
