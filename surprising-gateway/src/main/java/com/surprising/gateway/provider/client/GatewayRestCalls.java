package com.surprising.gateway.provider.client;

import java.net.URI;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Shared request shape for gateway clients that previously called {@code RestTemplate.exchange}. */
final class GatewayRestCalls {

    private GatewayRestCalls() {}

    static <T> ResponseEntity<T> exchange(RestClient client, URI uri, HttpMethod method,
                                          HttpHeaders headers, Object body, Class<T> type) {
        return spec(client, uri, method, headers, body).retrieve().toEntity(type);
    }

    static <T> ResponseEntity<T> exchange(RestClient client, URI uri, HttpMethod method,
                                          HttpHeaders headers, Object body, ParameterizedTypeReference<T> type) {
        return spec(client, uri, method, headers, body).retrieve().toEntity(type);
    }

    private static RestClient.RequestBodySpec spec(RestClient client, URI uri, HttpMethod method,
                                                   HttpHeaders headers, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri).headers(target -> {
            if (headers != null) {
                target.addAll(headers);
            }
        });
        if (body != null) {
            spec = spec.body(body);
        }
        return spec;
    }
}
