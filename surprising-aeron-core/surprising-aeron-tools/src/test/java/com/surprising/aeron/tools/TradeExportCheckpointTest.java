package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.*;

import com.surprising.product.api.ProductLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class TradeExportCheckpointTest {
    @TempDir Path directory;

    @Test
    void atomicallyReplacesAndRejectsCorruptionOrWrongProduct() throws Exception {
        Path path = directory.resolve("checkpoint");
        new TradeExportCheckpoint(ProductLine.SPOT, 100, 2, new byte[] {1, 2, 3}).write(path);
        assertThat(TradeExportCheckpoint.read(path, ProductLine.SPOT).tradeSequence()).isEqualTo(2);
        new TradeExportCheckpoint(ProductLine.SPOT, 200, 3, new byte[] {4, 5, 6}).write(path);
        assertThat(TradeExportCheckpoint.read(path, ProductLine.SPOT).logPosition()).isEqualTo(200);
        assertThatThrownBy(() -> TradeExportCheckpoint.read(path, ProductLine.OPTION))
                .isInstanceOf(java.io.IOException.class);
        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 33] ^= 1;
        Files.write(path, bytes);
        assertThatThrownBy(() -> TradeExportCheckpoint.read(path, ProductLine.SPOT))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("checksum");
    }
}
