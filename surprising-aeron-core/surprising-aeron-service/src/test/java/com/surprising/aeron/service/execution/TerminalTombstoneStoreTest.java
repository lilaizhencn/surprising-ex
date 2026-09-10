package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerminalTombstoneStoreTest {
    record Key(int type, long id) {}
    record Value(long user, String client, long sequence) {}
    record Client(int type, long user, String client) {}

    @Test void fifoGrowthWrapOverwriteAndClientCollisionsMatchOriginalMapBytes() throws Exception {
        var store = new TerminalTombstoneStore();
        var reference = new LinkedHashMap<Key, Value>();
        var clients = new HashMap<Client, Key>();
        for (int i = 1; i < 6000; i++) {
            int type = i % 4; long id = i % 913 + 1, user = i % 11 + 1;
            // Aa/BB have identical String hashes; exact comparison must distinguish them.
            String client = (i % 2 == 0 ? "Aa" : "BB") + i % 37;
            var key = new Key(type, id); var value = new Value(user, client, i);
            store.put(type, id, user, client, i);
            reference.put(key, value); clients.put(new Client(type, user, client), key);
            if (i % 29 == 0) {
                int maximum = 1000;
                store.trim(maximum);
                while (reference.size() > maximum) {
                    var entry = reference.pollFirstEntry();
                    clients.remove(new Client(entry.getKey().type(), entry.getValue().user(), entry.getValue().client()));
                }
                assertThat(bytes(store)).isEqualTo(bytes(reference));
                for (var entry : reference.entrySet()) assertThat(store.contains(entry.getKey().type(), entry.getKey().id())).isTrue();
                for (var entry : clients.keySet()) assertThat(store.containsClient(entry.type(), entry.user(), entry.client())).isTrue();
            }
        }
        var copy = store.copy(); byte[] snapshot = bytes(copy);
        store.trim(0);
        assertThat(store.size()).isZero();
        assertThat(bytes(copy)).isEqualTo(snapshot);
        assertThat(snapshot).isEqualTo(bytes(reference));
    }
    private static byte[] bytes(TerminalTombstoneStore store) throws Exception {
        var bytes = new ByteArrayOutputStream(); store.write(new DataOutputStream(bytes)); return bytes.toByteArray();
    }
    private static byte[] bytes(LinkedHashMap<Key,Value> values) throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); out.writeInt(values.size());
        for (var e : values.entrySet()) {
            out.writeByte(e.getKey().type()); out.writeLong(e.getKey().id()); out.writeLong(e.getValue().user()); out.writeLong(e.getValue().sequence());
            byte[] text = e.getValue().client().getBytes(StandardCharsets.UTF_8); out.writeInt(text.length); out.write(text);
        }
        return bytes.toByteArray();
    }
}
