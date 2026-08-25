package com.surprising.aeron.protocol;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

public final class CoreFactVerifier {

    private static final int ENVELOPE_VERSION = 1;
    private final String keyId;
    private final String keyFingerprint;
    private final PublicKey publicKey;

    private CoreFactVerifier(String keyId, String keyFingerprint, PublicKey publicKey) {
        this.keyId = keyId;
        this.keyFingerprint = keyFingerprint;
        this.publicKey = publicKey;
        if (!fingerprint(publicKey).equals(keyFingerprint)) {
            throw new IllegalStateException("Core fact public key fingerprint mismatch");
        }
    }

    public static CoreFactVerifier configured() {
        String keyId = required("surprising.aeron.integrity.key-id");
        String publicValue = required("surprising.aeron.integrity.public-key-x509");
        String fingerprint = required("surprising.aeron.integrity.public-key-sha256").toLowerCase(java.util.Locale.ROOT);
        return fromEncoded(keyId, publicValue, fingerprint);
    }

    public static CoreFactVerifier fromEncoded(String keyId, String publicValue, String fingerprint) {
        if (keyId == null || keyId.isBlank() || publicValue == null || publicValue.isBlank()
                || fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("Core fact verification material is required");
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicValue)));
            return new CoreFactVerifier(keyId.trim(), fingerprint.trim().toLowerCase(java.util.Locale.ROOT), key);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("invalid Aeron Core integrity verification configuration", exception);
        }
    }

    public void verify(CoreExportEvent event) {
        if (event == null || event.integrity() == null) {
            throw new IllegalStateException("missing Core fact integrity envelope");
        }
        CoreFactIntegrityView integrity = event.integrity();
        if (!keyId.equals(integrity.keyId()) || !keyFingerprint.equals(integrity.keyFingerprint())) {
            throw new IllegalStateException("unexpected Core fact integrity key");
        }
        byte[] payload = CoreExportCodec.integrityPayload(event);
        byte[] payloadHash = sha256(payload);
        if (!MessageDigest.isEqual(payloadHash, integrity.payloadHashUnsafe())) {
            throw new IllegalStateException("Core fact payload hash mismatch");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signedBytes(integrity.keyId(), integrity.keyFingerprint(), payloadHash));
            if (!verifier.verify(integrity.signatureUnsafe())) {
                throw new IllegalStateException("Core fact signature mismatch");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Core fact signature verification failed", exception);
        }
    }

    private static String fingerprint(PublicKey publicKey) {
        return HexFormat.of().formatHex(sha256(publicKey.getEncoded()));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] signedBytes(String keyId, String fingerprint, byte[] payloadHash) {
        byte[] key = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] fingerprintBytes = fingerprint.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(Integer.BYTES * 3 + key.length + fingerprintBytes.length + payloadHash.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(ENVELOPE_VERSION)
                .putInt(key.length).put(key)
                .putInt(fingerprintBytes.length).put(fingerprintBytes)
                .put(payloadHash)
                .array();
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing required property: " + name);
        return value.trim();
    }
}
