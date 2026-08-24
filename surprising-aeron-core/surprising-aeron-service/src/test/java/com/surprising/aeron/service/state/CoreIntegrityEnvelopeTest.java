package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreIntegrityEnvelopeTest {

    @Test
    void rejectsTamperingAndWrongFingerprint() throws Exception {
        KeyPair first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        FundsDelta delta = new FundsDelta(List.of(
                new FundsPosting("USDT", FundsPosting.OwnerKind.USER, 7,
                        FundsPosting.Subledger.AVAILABLE, -5),
                new FundsPosting("USDT", FundsPosting.OwnerKind.TREASURY, 0,
                        FundsPosting.Subledger.FEE, 5)));
        byte[] canonical = delta.canonicalBytes();
        CoreIntegrityEnvelope envelope = CoreIntegrityEnvelope.sign("core-a", first.getPrivate(),
                first.getPublic(), canonical);

        envelope.verify(canonical, first.getPublic());
        assertThat(envelope.payloadHash()).isEqualTo(CoreIntegrityEnvelope.sha256(canonical));
        byte[] tampered = canonical.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> envelope.verify(tampered, first.getPublic()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Core integrity signature mismatch");
        assertThatThrownBy(() -> envelope.verify(canonical, second.getPublic()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Core integrity key fingerprint mismatch");
    }
}
