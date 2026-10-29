package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.jpountz.lz4.LZ4Factory;
import org.junit.jupiter.api.Test;
import org.xerial.snappy.Snappy;

class CompressionCompatibilityTest {
    private static final byte[] PAYLOAD = "{\"productLine\":\"LINEAR_PERPETUAL\",\"balance\":9223372036854775807}"
            .repeat(20).getBytes(StandardCharsets.US_ASCII);

    @Test void patchedLz4ReadsExistingCompressedBytes() {
        // Produced by lz4-java 1.11.2 before the security update.
        byte[] existing = Base64.getDecoder().decode("/zF7InByb2R1Y3RMaW5lIjoiTElORUFSX1BFUlBFVFVBTCIsImJhbGFuY2UiOjkyMjMzNzIwMzY4NTQ3NzU4MDd9QAD/////rFA1ODA3fQ==");
        var codec = LZ4Factory.fastestJavaInstance();
        assertThat(codec.safeDecompressor().decompress(existing, PAYLOAD.length)).isEqualTo(PAYLOAD);
        assertThat(codec.safeDecompressor().decompress(codec.fastCompressor().compress(PAYLOAD), PAYLOAD.length)).isEqualTo(PAYLOAD);
    }

    @Test void patchedSnappyReadsExistingCompressedBytes() throws Exception {
        // Produced by snappy-java 1.1.10.7 before the security update.
        byte[] existing = Base64.getDecoder().decode("gArwQHsicHJvZHVjdExpbmUiOiJMSU5FQVJfUEVSUEVUVUFMIiwiYmFsYW5jZSI6OTIyMzM3MjAzNjg1NDc3NTgwN317/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA/kAA+kAA");
        assertThat(Snappy.uncompress(existing)).isEqualTo(PAYLOAD);
        assertThat(Snappy.uncompress(Snappy.compress(PAYLOAD))).isEqualTo(PAYLOAD);
    }
}
