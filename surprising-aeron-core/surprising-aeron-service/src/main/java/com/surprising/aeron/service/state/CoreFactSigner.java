package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreFactIntegrityView;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class CoreFactSigner {

    private static final CoreFactSigner IN_MEMORY = generated();
    private final String keyId;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private CoreFactSigner(String keyId, PrivateKey privateKey, PublicKey publicKey) {
        if (keyId == null || keyId.isBlank() || privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("invalid core fact signer");
        }
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static CoreFactSigner inMemory() {
        return IN_MEMORY;
    }

    public static CoreFactSigner configured() {
        String keyId = required("surprising.aeron.integrity.key-id");
        String privateValue = required("surprising.aeron.integrity.private-key-pkcs8");
        String publicValue = required("surprising.aeron.integrity.public-key-x509");
        String expectedFingerprint = required("surprising.aeron.integrity.public-key-sha256");
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateValue)));
            PublicKey publicKey = factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicValue)));
            CoreIntegrityEnvelope.requireFingerprint(publicKey, expectedFingerprint);
            return new CoreFactSigner(keyId, privateKey, publicKey);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("invalid Aeron Core integrity signing configuration", exception);
        }
    }

    public CoreFactIntegrityView sign(byte[] payload) {
        CoreIntegrityEnvelope envelope = CoreIntegrityEnvelope.sign(keyId, privateKey, publicKey, payload);
        return new CoreFactIntegrityView(envelope.keyId(), envelope.keyFingerprint(),
                envelope.payloadHash(), envelope.signature());
    }

    public com.surprising.aeron.protocol.CoreFactVerifier verifier() {
        String encoded = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        String fingerprint = java.util.HexFormat.of().formatHex(CoreIntegrityEnvelope.sha256(publicKey.getEncoded()));
        return com.surprising.aeron.protocol.CoreFactVerifier.fromEncoded(keyId, encoded, fingerprint);
    }

    private static CoreFactSigner generated() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            return new CoreFactSigner("in-memory", pair.getPrivate(), pair.getPublic());
        } catch (GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing required property: " + name);
        return value.trim();
    }
}
