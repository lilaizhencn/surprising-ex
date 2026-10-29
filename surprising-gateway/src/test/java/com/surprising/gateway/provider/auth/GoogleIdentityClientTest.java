package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.auth.client.GoogleIdentityClient;
import com.surprising.gateway.provider.config.GoogleLoginProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;

class GoogleIdentityClientTest {
    static final String WEB = "test-web.apps.googleusercontent.com", IOS = "test-ios.apps.googleusercontent.com",
            ANDROID = "test-android.apps.googleusercontent.com";
    HttpServer server;
    RSAKey key;
    NimbusJwtDecoder decoder;
    GoogleLoginProperties properties;
    Instant now;

    @BeforeEach void setup() throws Exception {
        now = Instant.now();
        properties = new GoogleLoginProperties();
        properties.setWebClientId(WEB);
        properties.setIosClientId(IOS);
        properties.setAndroidClientIds(List.of(ANDROID));
        key = new RSAKeyGenerator(2048).keyID("google-test").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/keys", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        decoder = GoogleIdentityClient.decoder("http://127.0.0.1:" + server.getAddress().getPort() + "/keys", new RestTemplate());
    }
    @AfterEach void close() { if (server != null) server.stop(0); }
    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(WEB).subject("google-123")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("nonce", "expected").claim("azp", WEB).claim("name", "开发者 👩‍💻");
    }
    String signed(JWTClaimsSet claims, RSAKey signer) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(signer)); return token.serialize();
    }
    GoogleIdentityClient.Profile validate(JWTClaimsSet.Builder claims, String nonce, String platform) throws Exception {
        return GoogleIdentityClient.validate(decoder.decode(signed(claims.build(), key)), properties, nonce, platform, now);
    }
    @Test void verifiesSignedIdentityAndBothGoogleIssuers() throws Exception {
        assertThat(validate(claims(), "expected", "WEB").displayName()).isEqualTo("开发者 👩‍💻");
        assertThat(validate(claims().issuer("accounts.google.com"), "expected", "WEB").subject()).isEqualTo("google-123");
        assertThat(validate(claims().claim("azp", null), "expected", "WEB").subject()).isEqualTo("google-123");
    }
    @Test void rejectsForgedSignatureAndIssuer() throws Exception {
        var forged = signed(claims().build(), new RSAKeyGenerator(2048).generate());
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> validate(claims().issuer("https://attacker.example"), "expected", "WEB")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> validate(claims().issuer(null), "expected", "WEB")).isInstanceOf(JwtException.class);
    }
    @Test void rejectsAudienceNonceExpiryAndMissingSubject() {
        assertThatThrownBy(() -> validate(claims().audience("other-client"), "expected", "WEB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().audience(List.of(WEB, "other")), "expected", "WEB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().audience((String) null), "expected", "WEB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims(), "wrong", "WEB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims(), null, "WEB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().expirationTime(Date.from(now.minusSeconds(1))).issueTime(Date.from(now.minusSeconds(100))), "expected", "WEB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().expirationTime(null), "expected", "WEB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().subject(null), "expected", "WEB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().issueTime(Date.from(now.plusSeconds(120))), "expected", "WEB")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nativeTokensMustBePresentedByConfiguredNativeClient() throws Exception {
        assertThat(validate(claims().claim("azp", IOS), null, "IOS").subject()).isEqualTo("google-123");
        assertThat(validate(claims().claim("azp", ANDROID), null, "ANDROID").subject()).isEqualTo("google-123");
        assertThatThrownBy(() -> validate(claims(), null, "ANDROID")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().claim("azp", IOS), null, "ANDROID")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().claim("azp", null), null, "ANDROID")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate(claims().claim("azp", ANDROID), "expected", "WEB")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void normalizesDisplayNameWithoutSplittingUnicode() throws Exception {
        assertThat(validate(claims().claim("name", "\u202E\n" + "🚀".repeat(140)), "expected", "WEB").displayName()).isEqualTo("🚀".repeat(128));
    }
    @Test void emptyConfigurationDisablesAllSurfaces() {
        var empty = new GoogleLoginProperties();
        assertThat(empty.enabled()).isFalse(); assertThat(empty.androidEnabled()).isFalse(); assertThat(empty.iosEnabled()).isFalse();
        empty.setWebClientId(WEB); empty.setAndroidClientIds(null);
        assertThat(empty.enabled()).isTrue(); assertThat(empty.androidEnabled()).isFalse();
    }
}
