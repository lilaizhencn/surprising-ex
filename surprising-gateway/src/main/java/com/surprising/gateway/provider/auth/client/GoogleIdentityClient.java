package com.surprising.gateway.provider.auth.client;

import com.surprising.gateway.provider.config.GoogleLoginProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Only signed Google ID tokens may cross this identity boundary. */
@Component
public class GoogleIdentityClient {
    private final GoogleLoginProperties properties;
    private final JwtDecoder decoder;

    @org.springframework.beans.factory.annotation.Autowired
    public GoogleIdentityClient(GoogleLoginProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        decoder = decoder("https://www.googleapis.com/oauth2/v3/certs", new RestTemplate(factory));
    }

    GoogleIdentityClient(GoogleLoginProperties properties, JwtDecoder decoder) {
        this.properties = properties;
        this.decoder = decoder;
    }

    public static NimbusJwtDecoder decoder(String jwksUri, RestTemplate http) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).restOperations(http)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), token ->
                isGoogleIssuer(token.getClaimAsString("iss"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }

    public Profile verify(String credential, String nonce, String platform) {
        if (!properties.enabled()) throw new IllegalStateException("Google login is not configured");
        if (credential == null || credential.length() > 16384 || credential.isBlank())
            throw new IllegalArgumentException("Invalid Google credential");
        try {
            return validate(decoder.decode(credential), properties, nonce, platform, Instant.now());
        } catch (BadJwtException | IllegalArgumentException failure) {
            // Provider tokens, claims and remote errors never enter API errors or logs.
            throw new IllegalArgumentException("Google authorization is invalid or expired");
        } catch (JwtException unavailable) {
            throw new Unavailable();
        }
    }

    public static Profile validate(Jwt token, GoogleLoginProperties properties, String nonce,
                                   String platform, Instant now) {
        String presenter = token.getClaimAsString("azp");
        boolean validPresenter = switch (platform) {
            case "WEB" -> (presenter == null || presenter.equals(properties.getWebClientId()))
                    && nonce != null && nonce.equals(token.getClaimAsString("nonce"));
            case "ANDROID" -> properties.androidEnabled() && presenter != null
                    && properties.getAndroidClientIds().contains(presenter);
            case "IOS" -> properties.iosEnabled() && properties.getIosClientId().equals(presenter);
            default -> false;
        };
        if (!properties.enabled() || !validPresenter
                || !isGoogleIssuer(token.getClaimAsString("iss"))
                || !List.of(properties.getWebClientId()).equals(token.getAudience())
                || token.getSubject() == null || !token.getSubject().matches("[A-Za-z0-9_-]{1,255}")
                || token.getIssuedAt() == null || token.getIssuedAt().isAfter(now.plusSeconds(30))
                || token.getExpiresAt() == null || !token.getExpiresAt().isAfter(now))
            throw new IllegalArgumentException("Invalid Google identity");
        String name = token.getClaimAsString("name");
        if (name == null) name = "";
        name = name.replaceAll("[\\p{Cc}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]", "").strip();
        if (name.isBlank()) name = "Google";
        name = name.substring(0, name.offsetByCodePoints(0, Math.min(name.codePointCount(0, name.length()), 128)));
        return new Profile(token.getSubject(), name);
    }

    public record Profile(String subject, String displayName) {}

    private static boolean isGoogleIssuer(String issuer) {
        return "https://accounts.google.com".equals(issuer) || "accounts.google.com".equals(issuer);
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("Google sign-in is temporarily unavailable"); }
    }
}
