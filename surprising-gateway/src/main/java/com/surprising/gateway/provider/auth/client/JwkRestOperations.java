package com.surprising.gateway.provider.auth.client;

import java.lang.reflect.Proxy;
import java.util.Objects;
import org.springframework.http.RequestEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;

/**
 * NimbusJwtDecoder still accepts only {@link RestOperations}. This adapter forwards the
 * single JWKS fetch it performs through {@link RestClient}.
 */
@SuppressWarnings({"deprecation", "removal"})
final class JwkRestOperations {

    private JwkRestOperations() {}

    static RestOperations adapt(RestClient client) {
        Objects.requireNonNull(client, "client");
        return (RestOperations) Proxy.newProxyInstance(
                RestOperations.class.getClassLoader(),
                new Class<?>[] {RestOperations.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "JwkRestOperations";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    if ("exchange".equals(method.getName()) && args != null && args.length == 2
                            && args[0] instanceof RequestEntity<?> request && args[1] instanceof Class<?> type) {
                        RestClient.RequestBodySpec spec = client.method(Objects.requireNonNull(request.getMethod()))
                                .uri(request.getUrl())
                                .headers(headers -> headers.addAll(request.getHeaders()));
                        if (request.getBody() != null) {
                            spec = spec.body(request.getBody());
                        }
                        return spec.retrieve().toEntity(type);
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
    }
}
