package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionChainContinuityAuditMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void runsFileBackedAuditAndEmitsBinaryPassResult() throws Exception {
        Path observations = temporaryDirectory.resolve("observations.tsv");
        Path clients = temporaryDirectory.resolve("clients.tsv");
        Files.writeString(observations, String.join("\n",
                "CORE\te1\t1\ttopic\t101\tone\t-\t0\t-",
                "KAFKA\te1\t1\ttopic\t101\tone\t-\t0\t40",
                "POSTGRES\te1\t1\ttopic\t101\tone\t-\t0\t-",
                "WEBSOCKET\te1\t1\ttopic\t101\tone\tclient-1\t0\t-"), StandardCharsets.UTF_8);
        Files.writeString(clients, "client-1\ttopic\t101\t1\t100\t110\t120\t0\t0\t0\n",
                StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();

        int exit = ProductionChainContinuityAuditMain.run(
                new String[]{"1", "1", "1", observations.toString(), clients.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("\"result\":\"PASS\"");
    }

    @Test
    void malformedObservationFailsClosed() throws Exception {
        Path observations = temporaryDirectory.resolve("bad.tsv");
        Path clients = temporaryDirectory.resolve("clients.tsv");
        Files.writeString(observations, "CORE\ttoo-few-columns\n", StandardCharsets.UTF_8);
        Files.writeString(clients, "", StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();

        int exit = ProductionChainContinuityAuditMain.run(
                new String[]{"1", "1", "1", observations.toString(), clients.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("\"result\":\"ERROR\"");
    }
}
