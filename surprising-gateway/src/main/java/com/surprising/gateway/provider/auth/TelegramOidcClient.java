package com.surprising.gateway.provider.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/** The only boundary allowed to trust externally supplied Telegram identity claims. */
@Component
public class TelegramOidcClient {
    static final String ISSUER = "https://oauth.telegram.org";
    private final String clientId, clientSecret, redirectUri;
    private final RestClient http;
    private final NimbusJwtDecoder decoder;

    public TelegramOidcClient(@Value("${telegram.login.client-id:}") String clientId,
                              @Value("${telegram.login.client-secret:}") String clientSecret,
                              @Value("${telegram.login.redirect-uri:}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        http = RestClient.builder().requestFactory(factory).build();
        decoder = decoder(clientId, ISSUER + "/.well-known/jwks.json", factory);
    }

    static NimbusJwtDecoder decoder(String clientId, String jwksUri,
                                    org.springframework.http.client.ClientHttpRequestFactory factory) {
        var result = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(factory)).build();
        result.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), token ->
                token.getAudience().contains(clientId) && token.getExpiresAt() != null
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return result;
    }

    public boolean enabled() {
        if (clientId.isBlank() || clientSecret.isBlank() || redirectUri.isBlank()) return false;
        try {
            var uri = java.net.URI.create(redirectUri);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && "/api/v1/auth/telegram/callback".equals(uri.getPath());
        } catch (IllegalArgumentException ex) { return false; }
    }

    public String authorizationUrl(String state, String nonce, String challenge) {
        if (!enabled()) throw new IllegalStateException("Telegram login is not configured");
        return org.springframework.web.util.UriComponentsBuilder.fromUriString(ISSUER + "/auth")
                .queryParam("client_id", clientId).queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code").queryParam("scope", "openid profile")
                .queryParam("state", state).queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
    }

    public Profile exchange(String code, String verifier, String expectedNonce) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("code_verifier", verifier);
        var response = http.post().uri(ISSUER + "/token")
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(TokenResponse.class);
        if (response == null || response.id_token() == null)
            throw new IllegalArgumentException("invalid Telegram response");
        return validate(decoder.decode(response.id_token()), expectedNonce, Instant.now());
    }

    static Profile validate(Jwt token, String expectedNonce, Instant now) {
        if (expectedNonce == null || !expectedNonce.equals(token.getClaimAsString("nonce"))
                || token.getSubject() == null || !token.getSubject().matches("[0-9]{1,32}")
                || token.getIssuedAt() == null || token.getIssuedAt().isAfter(now.plusSeconds(30))
                || token.getIssuedAt().isBefore(now.minusSeconds(600))
                || token.getExpiresAt() == null || !token.getExpiresAt().isAfter(now))
            throw new IllegalArgumentException("invalid Telegram identity");
        String name = token.getClaimAsString("name");
        if (name == null || name.isBlank()) name = token.getClaimAsString("preferred_username");
        if (name == null || name.isBlank()) name = "Telegram";
        name = name.replaceAll("[\\p{Cc}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]", "").strip();
        if (name.isBlank()) name = "Telegram";
        name = name.substring(0, name.offsetByCodePoints(0, Math.min(name.codePointCount(0, name.length()), 128)));
        return new Profile(token.getSubject(), name);
    }

    public record Profile(String subject, String displayName) {}
    record TokenResponse(String id_token) {}
}
