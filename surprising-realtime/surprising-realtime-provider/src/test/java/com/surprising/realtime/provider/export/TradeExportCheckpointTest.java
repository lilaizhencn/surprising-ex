package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;

import com.surprising.product.api.ProductLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class TradeExportCheckpointTest {
    @TempDir Path directory;

    @Test void commandRepairCannotStartFromARenamedOnlineCheckpoint() throws Exception {
        var online = directory.resolve("online.checkpoint");
        var repair = directory.resolve("receipts.command-repair");
        var checkpoint = new TradeExportCheckpoint(ProductLine.SPOT, 100, 0, new byte[]{1, 2, 3});
        checkpoint.write(online);
        Files.copy(online, repair);
        assertThatThrownBy(() -> TradeExportCheckpoint.read(repair, ProductLine.SPOT))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("purpose");
        checkpoint.write(repair);
        assertThat(TradeExportCheckpoint.read(repair, ProductLine.SPOT).logPosition()).isEqualTo(100);
        Files.copy(repair, online, StandardCopyOption.REPLACE_EXISTING);
        assertThatThrownBy(() -> TradeExportCheckpoint.read(online, ProductLine.SPOT))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("purpose");
        var config = new TradeExportProperties(directory, "unused", "unused", repair, 0, null, null, null);
        assertThatThrownBy(() -> new CommittedTradeExporter(config, ProductLine.SPOT, null,
                org.mockito.Mockito.mock(CommittedOrderProjectionRepository.class))
                .run(new java.util.concurrent.atomic.AtomicBoolean(false), ready -> {}, 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("live exporter");
    }

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
