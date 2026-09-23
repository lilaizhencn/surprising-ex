package com.surprising.aeron.tools.replay;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public final class SnapshotInspectMain {

    private SnapshotInspectMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: SnapshotInspectMain <PRODUCT_LINE> <snapshot-file>");
        }
        ProductLine productLine = ProductLine.requireExternalCode(args[0]);
        var manifest = TradingCoreRuntime.inspectSnapshot(productLine, Files.readAllBytes(Path.of(args[1])));
        log.info("{}", String.format(java.util.Locale.ROOT, "productLine=%s schemaVersion=%d snapshotId=%d coreSequence=%d "
                        + "appliedCommandCount=%d businessStateHash=%016x checksum=%016x%n",
                manifest.productLine(), manifest.schemaVersion(), manifest.snapshotId(), manifest.coreSequence(),
                manifest.appliedCommandCount(), manifest.businessStateHash(), manifest.checksum()).stripTrailing());
    }
}
