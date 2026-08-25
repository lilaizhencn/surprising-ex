package com.surprising.aeron.protocol;

public record CoreFactIntegrityView(String keyId, String keyFingerprint, byte[] payloadHash, byte[] signature) {

    public CoreFactIntegrityView {
        if (keyId == null || keyId.isBlank() || keyFingerprint == null || keyFingerprint.isBlank()
                || payloadHash == null || payloadHash.length != 32 || signature == null || signature.length == 0) {
            throw new IllegalArgumentException("invalid core fact integrity view");
        }
        payloadHash = payloadHash.clone();
        signature = signature.clone();
    }

    @Override
    public byte[] payloadHash() { return payloadHash.clone(); }

    byte[] payloadHashUnsafe() { return payloadHash; }

    @Override
    public byte[] signature() { return signature.clone(); }

    byte[] signatureUnsafe() { return signature; }
}
