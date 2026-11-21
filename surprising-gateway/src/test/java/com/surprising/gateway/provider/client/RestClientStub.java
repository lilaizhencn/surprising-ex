package com.surprising.gateway.provider.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mockito.Answers;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Fluent {@link RestClient} mock that records one scripted response or failure. */
public final class RestClientStub {

    private final RestClient client;
    private final RestClient.ResponseSpec response;

    private RestClientStub(RestClient client, RestClient.ResponseSpec response) {
        this.client = client;
        this.response = response;
    }

    public static RestClientStub create() {
        RestClient client = mock(RestClient.class);
        RestClient.RequestBodyUriSpec spec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
        when(client.method(any())).thenReturn(spec);
        when(spec.retrieve()).thenReturn(response);
        return new RestClientStub(client, response);
    }

    public RestClient client() {
        return client;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void respond(ResponseEntity<?> entity) {
        when(response.toEntity(any(Class.class))).thenReturn((ResponseEntity) entity);
        when(response.toEntity(any(ParameterizedTypeReference.class))).thenReturn((ResponseEntity) entity);
    }

    void fail(RuntimeException error) {
        when(response.toEntity(any(Class.class))).thenThrow(error);
        when(response.toEntity(any(ParameterizedTypeReference.class))).thenThrow(error);
    }
}
