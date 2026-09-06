package com.surprising.aeron.tools;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.ClusterTool;
import io.aeron.cluster.RecordingLog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small, synchronous functional fault driver. Never packaged in the production tools jar. */
public final class FaultClientMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProductLine PRODUCT = ProductLine.requireExternalCode(
            System.getProperty("surprising.aeron.product-line", "SPOT"));

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            File directory = new File(args[1]);
            if (args[0].equals("snapshot")) {
                if (!ClusterTool.snapshot(directory, System.out)) throw new IllegalStateException("snapshot rejected");
            } else if (args[0].equals("recordings")) {
                try (RecordingLog log = new RecordingLog(directory, false)) {
                    System.out.println("QA " + JSON.writeValueAsString(log.entries()));
                }
            } else if (args[0].equals("snapshot-state")) {
                try (RecordingLog log = new RecordingLog(directory, false)) {
                    var snapshot = log.getLatestSnapshot(0);
                    if (snapshot == null) throw new IllegalStateException("no complete service snapshot");
                    File archive = new File(directory.getParentFile(), "archive");
                    File[] segments = archive.listFiles((dir, name) -> name.startsWith(snapshot.recordingId + "-") && name.endsWith(".rec"));
                    if (segments == null || segments.length != 1) throw new IllegalStateException("small fixture expects one segment");
                    ByteArrayOutputStream payload = new ByteArrayOutputStream();
                    try (RandomAccessFile input = new RandomAccessFile(segments[0], "r")) {
                        long offset = 0;
                        while (offset + 32 <= input.length()) {
                            input.seek(offset);
                            byte[] header = new byte[32];
                            input.readFully(header);
                            var frame = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                            int length = frame.getInt(0);
                            if (length == 0) break;
                            if (length < 32 || length > 1_048_576 || payload.size() > 64 * 1024 * 1024)
                                throw new IllegalStateException("invalid or excessive snapshot frame");
                            if (frame.getShort(6) == 1) {
                                byte[] data = new byte[length - 32];
                                input.readFully(data);
                                // ClusteredServiceContainer writes its own SBE metadata before application bytes.
                                if (payload.size() > 0 || data.length >= 4
                                        && java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() == 0x5358534e)
                                    payload.write(data);
                            }
                            offset += (length + 31) & ~31;
                        }
                    }
                    try (var state = com.surprising.aeron.service.CoreProbeState.fromSnapshot(PRODUCT, payload.toByteArray())) {
                        emit(Map.of("logPosition", snapshot.logPosition, "businessHash", state.tradingState().businessStateHash(),
                                "coreSequence", state.committedCoreSequence()));
                    }
                }
            } else {
                ClusterTool.listMembers(System.out, directory);
            }
            return;
        }
        SurprisingAeronClient client = null;
        Map<String, CoreMessage> commands = new HashMap<>();
        long correlation = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode input = JSON.readTree(line);
                    String op = input.path("op").asText();
                    if (op.equals("close")) {
                        if (client != null) client.close();
                        client = null;
                        emit(Map.of("ok", true));
                        continue;
                    }
                    if (client == null) client = SurprisingAeronClient.connect(PRODUCT,
                            Arrays.asList(System.getProperty("surprising.aeron.hostnames").split(",")),
                            System.getProperty("surprising.aeron.egress-hostname"), Duration.ofSeconds(6));
                    long user = input.path("user").asLong(1001);
                    CoreMessageType type;
                    byte[] payload;
                    boolean query = false;
                    switch (op) {
                        case "init" -> {
                            type = CoreMessageType.UPSERT_INSTRUMENT;
                            payload = TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                                    "QA-BTC-USDT", 1, 0, "BTC", "USDT", "USDT", 1, 1, 1,
                                    100_000, 50_000, 0, 0, 0, -1, 0));
                        }
                        case "adjust" -> {
                            type = CoreMessageType.ADJUST_BALANCE;
                            payload = TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(
                                    input.path("asset").asText("USDT"), input.path("units").asLong()));
                        }
                        case "place" -> {
                            type = CoreMessageType.PLACE_ORDER;
                            payload = TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                                    input.path("order").asLong(), "QA-BTC-USDT", 1,
                                    CoreOrderSide.valueOf(input.path("side").asText()), input.path("price").asLong(100),
                                    input.path("qty").asLong(), false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "qa-" + input.path("order").asLong()));
                        }
                        case "cancel" -> {
                            type = CoreMessageType.CANCEL_ORDER;
                            payload = TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(input.path("order").asLong()));
                        }
                        default -> {
                            type = CoreMessageType.valueOf(input.path("type").asText("USER_STATE_QUERY"));
                            payload = Base64.getDecoder().decode(input.path("payload").asText(""));
                            query = type.kind() == WireMessageKind.QUERY;
                        }
                    }
                    long id = ++correlation;
                    String key = input.path("key").asText("query-" + id);
                    UUID uuid = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
                    CoreMessage request = new CoreMessage(query
                            ? CoreMessageHeader.query(type, uuid, PRODUCT, CommandSource.OPERATIONS, 777, 0, user, id, id)
                            : CoreMessageHeader.command(type, uuid, PRODUCT, CommandSource.OPERATIONS, 777, id, user, id, id), payload);
                    if (!query) {
                        CoreMessage previous = commands.putIfAbsent(key, request);
                        if (previous != null) request = previous;
                    }
                    if (input.path("offerOnly").asBoolean(false)) {
                        emit(Map.of("offered", client.offer(request)));
                        continue;
                    }
                    CoreResponse response = client.submit(request);
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("ok", response.status() == ResponseStatus.OK || response.commandStatus() == ResponseStatus.APPLIED);
                    output.put("status", response.status());
                    output.put("commandStatus", response.commandStatus());
                    output.put("resultCode", response.resultCode());
                    output.put("position", response.committedCoreSequence());
                    output.put("hash", response.stateHash());
                    output.put("data", Base64.getEncoder().encodeToString(response.data()));
                    if (type == CoreMessageType.USER_STATE_QUERY && response.status() == ResponseStatus.OK)
                        output.put("user", CoreStateQueryCodec.decodeUserState(response.data()));
                    emit(output);
                } catch (Exception failure) {
                    emit(Map.of("ok", false, "error", failure.toString()));
                    if (client != null) client.close();
                    client = null;
                }
            }
        } finally {
            if (client != null) client.close();
        }
    }

    private static void emit(Object value) {
        System.out.println("QA " + JSON.writeValueAsString(value));
        System.out.flush();
    }
}
