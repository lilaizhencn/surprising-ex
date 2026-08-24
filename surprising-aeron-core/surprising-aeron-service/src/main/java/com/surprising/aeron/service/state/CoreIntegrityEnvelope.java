package com.surprising.aeron.service.state;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.HexFormat;

public final class CoreIntegrityEnvelope {

    private static final int VERSION = 1;

    private final String keyId;
    private final String keyFingerprint;
    private final byte[] payloadHash;
    private final byte[] signature;

    private CoreIntegrityEnvelope(String keyId, String keyFingerprint, byte[] payloadHash, byte[] signature) {
        if (keyId == null || keyId.isBlank() || keyFingerprint == null || keyFingerprint.isBlank()
                || payloadHash == null || payloadHash.length != 32 || signature == null || signature.length == 0) {
            throw new IllegalArgumentException("invalid Core integrity envelope");
        }
        this.keyId = keyId;
        this.keyFingerprint = keyFingerprint;
        this.payloadHash = payloadHash.clone();
        this.signature = signature.clone();
    }

    public static CoreIntegrityEnvelope sign(
            String keyId,
            PrivateKey privateKey,
            PublicKey publicKey,
            byte[] payload) {
        if (privateKey == null || publicKey == null || payload == null) {
            throw new IllegalArgumentException("Core integrity signing material is required");
        }
        String fingerprint = fingerprint(publicKey);
        byte[] hash = sha256(payload);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(signedBytes(keyId, fingerprint, hash));
            return new CoreIntegrityEnvelope(keyId, fingerprint, hash, signer.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Core integrity signing failed", exception);
        }
    }

    public void verify(byte[] payload, PublicKey publicKey) {
        if (payload == null || publicKey == null) {
            throw new IllegalArgumentException("Core integrity verification material is required");
        }
        if (!keyFingerprint.equals(fingerprint(publicKey))) {
            throw new IllegalStateException("Core integrity key fingerprint mismatch");
        }
        byte[] actualHash = sha256(payload);
        if (!MessageDigest.isEqual(payloadHash, actualHash)) {
            throw new IllegalStateException("Core integrity signature mismatch");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signedBytes(keyId, keyFingerprint, payloadHash));
            if (!verifier.verify(signature)) {
                throw new IllegalStateException("Core integrity signature mismatch");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Core integrity signature mismatch", exception);
        }
    }

    public static void requireFingerprint(PublicKey publicKey, String expectedFingerprint) {
        if (publicKey == null || expectedFingerprint == null
                || !fingerprint(publicKey).equals(expectedFingerprint.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("Core integrity key fingerprint mismatch");
        }
    }

    public static byte[] sha256(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Core integrity value is required");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public String keyId() {
        return keyId;
    }

    public String keyFingerprint() {
        return keyFingerprint;
    }

    public byte[] payloadHash() {
        return payloadHash.clone();
    }

    public byte[] signature() {
        return signature.clone();
    }

    private static String fingerprint(PublicKey publicKey) {
        return HexFormat.of().formatHex(sha256(publicKey.getEncoded()));
    }

    private static byte[] signedBytes(String keyId, String fingerprint, byte[] payloadHash) {
        byte[] key = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] fingerprintBytes = fingerprint.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(Integer.BYTES * 3 + key.length + fingerprintBytes.length + payloadHash.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(VERSION)
                .putInt(key.length).put(key)
                .putInt(fingerprintBytes.length).put(fingerprintBytes)
                .put(payloadHash)
                .array();
    }
}
