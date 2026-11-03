package com.surprising.aeron.service.result;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 资金身份完整 UUID、指纹冲突及快照顺序必须与旧格式一致。 */
class FundsCommandIndexTest {
    @Test
    void fullUuidAndFingerprintRemainStableAfterSlotReuse() {
        try (var index = new FundsCommandIndex()) {
            byte[] firstBytes = new byte[CommandFingerprint.LENGTH];
            firstBytes[0] = 1;
            var first = CommandFingerprint.fromBytes(firstBytes);
            var reusable = first.copyIntoSlot(null);
            index.put(new UUID(1, 7), reusable);
            byte[] secondBytes = new byte[CommandFingerprint.LENGTH];
            secondBytes[0] = 2;
            CommandFingerprint.fromBytes(secondBytes).copyIntoSlot(reusable);
            assertThat(index.get(new UUID(1, 7))).isEqualTo(first);
            assertThat(index.get(new UUID(2, 7))).isNull();
            assertThat(index.match(1, 7, first)).isEqualTo(FundsCommandIndex.Match.SAME);
            assertThat(index.match(1, 7, reusable)).isEqualTo(FundsCommandIndex.Match.CONFLICT);
            assertThat(index.match(2, 7, first)).isEqualTo(FundsCommandIndex.Match.ABSENT);
            assertThatThrownBy(() -> index.put(new UUID(1, 7), reusable))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflict");
            index.put(new UUID(1, 7), first);
            index.put(new UUID(2, 7), reusable);
            assertThat(index.get(new UUID(2, 7))).isEqualTo(reusable);
        }
    }

    @Test
    void snapshotKeepsUnsignedBigEndianUuidOrder() throws Exception {
        try (var index = new FundsCommandIndex()) {
            var fingerprint = CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]);
            index.put(new UUID(-1, 0), fingerprint);
            index.put(new UUID(1, -1), fingerprint);
            index.put(new UUID(1, 0), fingerprint);
            var bytes = new ByteArrayOutputStream();
            index.write(new DataOutputStream(bytes));
            var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()));
            assertThat(input.readInt()).isEqualTo(3);
            assertThat(new UUID(input.readLong(), input.readLong())).isEqualTo(new UUID(1, 0));
            input.skipNBytes(CommandFingerprint.LENGTH);
            assertThat(new UUID(input.readLong(), input.readLong())).isEqualTo(new UUID(1, -1));
            input.skipNBytes(CommandFingerprint.LENGTH);
            assertThat(new UUID(input.readLong(), input.readLong())).isEqualTo(new UUID(-1, 0));
            input.skipNBytes(CommandFingerprint.LENGTH);
            assertThat(input.available()).isZero();
        }
    }

    @Test
    void growthPreservesEveryIdentityAndSnapshotSortsUnsignedUuids() throws Exception {
        try (var index = new FundsCommandIndex()) {
            var fingerprint = CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]);
            var expected = new java.util.ArrayList<UUID>();
            for (int i = 0; i < 1500; i++) {
                UUID id = new UUID(Long.rotateLeft(i * 0x9E3779B97F4A7C15L, i & 63),
                        Long.reverse(i * 0xC2B2AE3D27D4EB4FL));
                expected.add(id);
                index.put(id, fingerprint);
            }
            expected.sort((left, right) -> {
                int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
                return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
            });
            var bytes = new ByteArrayOutputStream();
            index.write(new DataOutputStream(bytes));
            var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()));
            assertThat(input.readInt()).isEqualTo(expected.size());
            for (UUID id : expected) {
                assertThat(index.get(id)).isEqualTo(fingerprint);
                assertThat(new UUID(input.readLong(), input.readLong())).isEqualTo(id);
                input.skipNBytes(CommandFingerprint.LENGTH);
            }
            assertThat(input.available()).isZero();
        }
    }
}
