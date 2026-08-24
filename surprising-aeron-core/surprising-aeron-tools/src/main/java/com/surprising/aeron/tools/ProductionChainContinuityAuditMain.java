package com.surprising.aeron.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ProductionChainContinuityAuditMain {

    private ProductionChainContinuityAuditMain() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream output) {
        try {
            if (args.length != 5) {
                throw new IllegalArgumentException(
                        "usage: <first-core-sequence> <last-core-sequence> <projector-watermark> "
                                + "<observations.tsv> <clients.tsv>");
            }
            var scope = new ProductionChainContinuityAuditor.Scope(
                    Long.parseLong(args[0]), Long.parseLong(args[1]), Long.parseLong(args[2]));
            List<ProductionChainContinuityAuditor.Observation> observations = observations(Path.of(args[3]));
            List<ProductionChainContinuityAuditor.ClientCheckpoint> clients = clients(Path.of(args[4]));
            ProductionChainContinuityAuditor.Report report =
                    new ProductionChainContinuityAuditor().audit(scope, observations, clients);
            output.println(json(report));
            return report.passed() ? 0 : 1;
        } catch (Exception exception) {
            output.println("{\"result\":\"ERROR\",\"error\":\"" + escape(message(exception)) + "\"}");
            return 2;
        }
    }

    private static List<ProductionChainContinuityAuditor.Observation> observations(Path path) throws IOException {
        List<ProductionChainContinuityAuditor.Observation> result = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 9) {
                throw new IllegalArgumentException(path + ":" + lineNumber + " expected 9 tab-separated fields");
            }
            result.add(new ProductionChainContinuityAuditor.Observation(
                    ProductionChainContinuityAuditor.Layer.valueOf(fields[0]),
                    fields[1], Long.parseLong(fields[2]), fields[3], nullableLong(fields[4]), fields[5],
                    nullableText(fields[6]), Integer.parseInt(fields[7]), nullableLong(fields[8])));
        }
        return List.copyOf(result);
    }

    private static List<ProductionChainContinuityAuditor.ClientCheckpoint> clients(Path path) throws IOException {
        List<ProductionChainContinuityAuditor.ClientCheckpoint> result = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 10) {
                throw new IllegalArgumentException(path + ":" + lineNumber + " expected 10 tab-separated fields");
            }
            result.add(new ProductionChainContinuityAuditor.ClientCheckpoint(
                    fields[0], fields[1], Long.parseLong(fields[2]), Long.parseLong(fields[3]),
                    Long.parseLong(fields[4]), Long.parseLong(fields[5]), Long.parseLong(fields[6]),
                    Long.parseLong(fields[7]), Long.parseLong(fields[8]), Long.parseLong(fields[9])));
        }
        return List.copyOf(result);
    }

    private static String json(ProductionChainContinuityAuditor.Report report) {
        return "{\"result\":\"" + (report.passed() ? "PASS" : "FAIL") + "\""
                + ",\"coreMissing\":" + report.coreMissing()
                + ",\"coreDuplicates\":" + report.coreDuplicates()
                + ",\"coreOutOfOrder\":" + report.coreOutOfOrder()
                + ",\"mutations\":" + report.mutations()
                + ",\"kafkaRedeliveries\":" + report.kafkaRedeliveries()
                + ",\"missingKafkaEvents\":" + report.missingKafkaEvents()
                + ",\"postgresDuplicateFacts\":" + report.postgresDuplicateFacts()
                + ",\"missingProjectedFacts\":" + report.missingProjectedFacts()
                + ",\"webSocketRedeliveries\":" + report.webSocketRedeliveries()
                + ",\"permanentWebSocketLosses\":" + report.permanentWebSocketLosses()
                + ",\"reconnects\":" + report.reconnects()
                + ",\"authenticationFailures\":" + report.authenticationFailures()
                + ",\"queueRejections\":" + report.queueRejections()
                + ",\"subscriptionOrderViolations\":" + report.subscriptionOrderViolations()
                + ",\"staleCatchUps\":" + report.staleCatchUps()
                + ",\"unexpectedFacts\":" + report.unexpectedFacts()
                + ",\"duplicateClientCheckpoints\":" + report.duplicateClientCheckpoints()
                + ",\"missingClientCheckpoints\":" + report.missingClientCheckpoints() + "}";
    }

    private static Long nullableLong(String value) {
        return "-".equals(value) ? null : Long.valueOf(value);
    }

    private static String nullableText(String value) {
        return "-".equals(value) ? null : value;
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
