package com.surprising.gateway.provider.auth.service;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

@Service
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT = "pbkdf2_sha256";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private final SecureRandom secureRandom = new SecureRandom();
    private final java.util.concurrent.Semaphore hashes;
    private final String dummyHash;

    public PasswordHasher(com.surprising.gateway.provider.config.GatewayLimitProperties limits) {
        hashes = new java.util.concurrent.Semaphore(limits.getConcurrentPasswordHashes());
        dummyHash = hash(java.util.UUID.randomUUID().toString());
    }

    public String hash(String password) {
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new IllegalArgumentException("password length must be 8-128");
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        return FORMAT + "$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derived);
    }

    public boolean matches(String password, String storedHash) {
        if (password == null || password.length() > 128) {
            return false;
        }
        boolean missing = storedHash == null;
        String[] parts = (missing ? dummyHash : storedHash).split("\\$");
        if (parts.length != 4 || !FORMAT.equals(parts[0])) {
            return false;
        }
        try {
            if (!Integer.toString(ITERATIONS).equals(parts[1])) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != SALT_LENGTH_BYTES || expected.length != KEY_LENGTH_BITS / 8) return false;
            boolean matches = java.security.MessageDigest.isEqual(expected, derive(password, salt, ITERATIONS));
            return !missing && matches;
        } catch (IllegalArgumentException invalidHash) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        if (!hashes.tryAcquire()) throw new com.surprising.gateway.provider.service.GatewayRequestLimits.Rejection(
                com.surprising.gateway.provider.service.GatewayRequestLimits.Reason.GATEWAY_BUSY, "PASSWORD", 1000);
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            try { return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded(); }
            finally { spec.clearPassword(); }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to derive password hash", ex);
        } finally { hashes.release(); }
    }
}
