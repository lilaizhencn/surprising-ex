package com.surprising.aeron.tools;

import com.surprising.aeron.service.CoreProbeState;
import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SnapshotInspectMain {

    private SnapshotInspectMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: SnapshotInspectMain <PRODUCT_LINE> <snapshot-file>");
        }
        ProductLine productLine = ProductLine.requireExternalCode(args[0]);
        var manifest = CoreProbeState.inspectSnapshot(productLine, Files.readAllBytes(Path.of(args[1])));
        System.out.printf("productLine=%s schemaVersion=%d appliedCommandCount=%d businessStateHash=%016x "
                        + "exportAck=%d exportNext=%d exportPending=%d checksum=%08x%n",
                manifest.productLine(), manifest.schemaVersion(), manifest.appliedCommandCount(),
                manifest.businessStateHash(), manifest.exportStatus().acknowledgedSequence(),
                manifest.exportStatus().nextSequence(), manifest.exportStatus().pendingCount(),
                manifest.checksum());
    }
}
