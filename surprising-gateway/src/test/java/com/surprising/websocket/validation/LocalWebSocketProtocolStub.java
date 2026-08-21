package com.surprising.websocket.validation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalWebSocketProtocolStub implements AutoCloseable {

    private static final Pattern TOKEN = Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"token-(\\d+)\\\"");
    private static final Pattern CHANNEL = Pattern.compile("\\\"channel\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final List<Long> history = new ArrayList<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicLong acceptedConnections = new AtomicLong();
    private final AtomicLong receivedCommands = new AtomicLong();
    private final AtomicLong sentAuthenticationErrors = new AtomicLong();
    private final AtomicLong handshakeResponses = new AtomicLong();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();
    private final Thread acceptThread;

    LocalWebSocketProtocolStub() throws IOException {
        serverSocket = new ServerSocket(0, 128, java.net.InetAddress.getLoopbackAddress());
        acceptThread = Thread.ofVirtual().name("ws-audit-stub-accept").start(this::accept);
    }

    java.net.URI uri() {
        return java.net.URI.create("ws://127.0.0.1:" + serverSocket.getLocalPort() + "/ws/v1");
    }

    long acceptedConnections() {
        return acceptedConnections.get();
    }

    long receivedCommands() {
        return receivedCommands.get();
    }

    long sentAuthenticationErrors() {
        return sentAuthenticationErrors.get();
    }

    long handshakeResponses() {
        return handshakeResponses.get();
    }

    String lastFailure() {
        return lastFailure.get();
    }

    synchronized void broadcast(long sequence) {
        history.add(sequence);
        for (Connection connection : List.copyOf(connections)) {
            connection.sendSequence(sequence);
        }
    }

    void announceCatchUp(long sequence) {
        for (Connection connection : List.copyOf(connections)) {
            connection.sendCatchUp(sequence);
        }
    }

    void disconnectClients() {
        for (Connection connection : List.copyOf(connections)) {
            connection.disconnect();
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ex) {
            lastFailure.set(ex.toString());
        }
        disconnectClients();
        acceptThread.interrupt();
    }

    private void accept() {
        while (open.get()) {
            try {
                Socket socket = serverSocket.accept();
                acceptedConnections.incrementAndGet();
                Thread.ofVirtual().name("ws-audit-stub-connection").start(() -> handle(socket));
            } catch (IOException ex) {
                if (open.get()) {
                    throw new IllegalStateException("local WebSocket stub accept failed", ex);
                }
            }
        }
    }

    private void handle(Socket socket) {
        Connection connection = null;
        try {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            handshake(input, output);
            connection = new Connection(socket, input, output);
            connections.add(connection);
            connection.readCommands();
        } catch (IOException ex) {
            lastFailure.set(ex.toString());
        } finally {
            if (connection != null) {
                connections.remove(connection);
                connection.disconnect();
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handshake(BufferedInputStream input, BufferedOutputStream output) throws IOException {
        String request = readHeaders(input);
        String key = null;
        for (String line : request.split("\\r?\\n")) {
            if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18)) {
                key = line.substring(18).trim();
            }
        }
        if (key == null) {
            throw new IOException("missing WebSocket key");
        }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + MAGIC).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (Exception ex) {
            throw new IOException(ex);
        }
        output.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        handshakeResponses.incrementAndGet();
    }

    private static String readHeaders(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16_384) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("connection closed during handshake");
            }
            bytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) {
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("WebSocket headers too large");
    }

    private final class Connection {
        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedOutputStream output;
        private final Set<String> channels = ConcurrentHashMap.newKeySet();
        private volatile long userId;

        private Connection(Socket socket, BufferedInputStream input, BufferedOutputStream output) {
            this.socket = socket;
            this.input = input;
            this.output = output;
        }

        private void readCommands() throws IOException {
            while (!socket.isClosed()) {
                String command = readClientTextFrame(input);
                if (command == null) {
                    return;
                }
                receivedCommands.incrementAndGet();
                if (command.contains("\"op\":\"authenticate\"")) {
                    authenticate(command);
                } else if (command.contains("\"op\":\"subscribe\"")) {
                    subscribe(command);
                }
            }
        }

        private void authenticate(String command) throws IOException {
            Matcher matcher = TOKEN.matcher(command);
            if (!matcher.find()) {
                sentAuthenticationErrors.incrementAndGet();
                sendText("{\"op\":\"error\",\"error\":\"authentication rejected\"}");
                return;
            }
            userId = Long.parseLong(matcher.group(1));
            sendText("{\"op\":\"authenticated\",\"userId\":" + userId + "}");
        }

        private void subscribe(String command) throws IOException {
            Matcher matcher = CHANNEL.matcher(command);
            if (userId <= 0L || !matcher.find()) {
                sendText("{\"op\":\"error\",\"error\":\"subscription rejected\"}");
                return;
            }
            String channel = matcher.group(1);
            channels.add(channel);
            sendText("{\"op\":\"subscribed\",\"channel\":\"" + channel + "\"}");
            List<Long> replay;
            synchronized (LocalWebSocketProtocolStub.this) {
                replay = List.copyOf(history);
            }
            for (long sequence : replay) {
                sendEvent(channel, sequence);
            }
            sendCatchUp(replay.isEmpty() ? 0L : replay.getLast());
        }

        private void sendSequence(long sequence) {
            for (String channel : channels) {
                sendEvent(channel, sequence);
            }
        }

        private void sendEvent(String channel, long sequence) {
            String topic = "orders".equals(channel)
                    ? "surprising.linear-perp.core.events.v1"
                    : "surprising.linear-perp.price.events.v1";
            String userPart = "orders".equals(channel) ? Long.toString(userId) : "public";
            String message = "{\"op\":\"event\",\"channel\":\"" + channel
                    + "\",\"eventTime\":\"" + Instant.ofEpochMilli(1_700_000_000_000L + sequence)
                    + "\",\"data\":{\"eventId\":\"" + channel + '-' + userPart + '-' + sequence
                    + "\",\"exportSequence\":" + sequence + ",\"topic\":\"" + topic + "\"}}";
            try {
                sendText(message);
            } catch (IOException ignored) {
                disconnect();
            }
        }

        private void sendCatchUp(long sequence) {
            for (String channel : channels) {
                try {
                    sendText("{\"op\":\"caught_up\",\"channel\":\"" + channel
                            + "\",\"coreSequence\":" + sequence + "}");
                } catch (IOException ignored) {
                    disconnect();
                }
            }
        }

        private synchronized void sendText(String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            output.write(0x81);
            if (payload.length <= 125) {
                output.write(payload.length);
            } else if (payload.length <= 65_535) {
                output.write(126);
                output.write((payload.length >>> 8) & 0xff);
                output.write(payload.length & 0xff);
            } else {
                output.write(127);
                output.write(ByteBuffer.allocate(Long.BYTES).putLong(payload.length).array());
            }
            output.write(payload);
            output.flush();
        }

        private void disconnect() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readClientTextFrame(BufferedInputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            return null;
        }
        int second = input.read();
        if (second < 0) {
            return null;
        }
        int opcode = first & 0x0f;
        long length = second & 0x7f;
        if (length == 126) {
            length = (input.read() << 8) | input.read();
        } else if (length == 127) {
            length = ByteBuffer.wrap(input.readNBytes(Long.BYTES)).getLong();
        }
        byte[] mask = (second & 0x80) == 0 ? null : input.readNBytes(4);
        byte[] payload = input.readNBytes(Math.toIntExact(length));
        if (mask != null) {
            for (int index = 0; index < payload.length; index++) {
                payload[index] = (byte) (payload[index] ^ mask[index % mask.length]);
            }
        }
        if (opcode == 0x8) {
            return null;
        }
        if (opcode != 0x1) {
            return "";
        }
        return new String(payload, StandardCharsets.UTF_8);
    }
}
