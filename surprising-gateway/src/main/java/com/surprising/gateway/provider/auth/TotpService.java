package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int CODE_MODULUS = 1_000_000;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final GatewayProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public TotpService(GatewayProperties properties) {
        this.properties = properties;
    }

    public String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public String provisioningUri(String username, String secret) {
        String issuer = properties.getSecurity().getIssuer();
        String label = issuer + ":" + username;
        return "otpauth://totp/" + urlEncode(label)
                + "?secret=" + urlEncode(secret)
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS
                + "&period=" + PERIOD_SECONDS;
    }

    public boolean verify(String secret, String code, Instant now) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            return false;
        }
        long counter = now.getEpochSecond() / PERIOD_SECONDS;
        for (long offset = -1; offset <= 1; offset++) {
            String expected = code(secret, counter + offset);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    normalized.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    public String encryptSecret(String secret) {
        try {
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(nonce.length + encrypted.length);
            buffer.put(nonce);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encrypt mfa secret", ex);
        }
    }

    public String decryptSecret(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length <= GCM_NONCE_BYTES) {
                throw new IllegalArgumentException("invalid mfa secret ciphertext");
            }
            byte[] nonce = Arrays.copyOfRange(combined, 0, GCM_NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_NONCE_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to decrypt mfa secret", ex);
        }
    }

    String code(String secret, Instant now) {
        return code(secret, now.getEpochSecond() / PERIOD_SECONDS);
    }

    private String code(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % CODE_MODULUS);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid mfa secret", ex);
        }
    }

    private SecretKeySpec encryptionKey() {
        try {
            String configured = properties.getSecurity().getMfaSecretEncryptionKey();
            String material = configured == null || configured.isBlank()
                    ? properties.getSecurity().getJwtSecret()
                    : configured;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("failed to derive mfa encryption key", ex);
        }
    }

    private String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().replace(" ", "");
        return normalized.matches("\\d{6}") ? normalized : null;
    }

    private static String base32Encode(byte[] bytes) {
        StringBuilder output = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte item : bytes) {
            buffer = (buffer << 8) | (item & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            output.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return output.toString();
    }

    private static byte[] base32Decode(String value) {
        String normalized = value == null ? "" : value.trim().replace("=", "").replace(" ", "")
                .toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("mfa secret is required");
        }
        ByteBuffer output = ByteBuffer.allocate(normalized.length() * 5 / 8 + 1);
        int buffer = 0;
        int bitsLeft = 0;
        for (char item : normalized.toCharArray()) {
            int index = BASE32_ALPHABET.indexOf(item);
            if (index < 0) {
                throw new IllegalArgumentException("mfa secret contains invalid characters");
            }
            buffer = (buffer << 5) | index;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output.put((byte) ((buffer >> (bitsLeft - 8)) & 0xff));
                bitsLeft -= 8;
            }
        }
        return Arrays.copyOf(output.array(), output.position());
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
