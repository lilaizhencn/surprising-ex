package com.surprising.aeron.service.core.identity;

import com.surprising.aeron.service.account.ClientOrderIdRegistry;
import com.surprising.aeron.service.core.AccountBook;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** 测试在静止边界观察存活身份，用于比较索引内容；不保存分配游标，也不提供恢复入口。 */
public final class IdentityTestContents {
    private IdentityTestContents() {}

    public static Map<String, Object> capture(IdIndex identities) {
        identities.assertOwner();
        Map<List<Object>, Long> clients = new HashMap<>();
        AccountBook accountBook = field(identities, "clientRuntime");
        if (accountBook != null) {
            for (int lane = 0; lane < accountBook.accountLaneCount(); lane++)
                clients(accountBook.accountLane(lane).clientIdentities(), clients);
        } else {
            LongObjectHashMap<ClientOrderIdRegistry> tables = field(identities, "unboundClients");
            if (tables != null) tables.forEachValue(table -> clients(table, clients));
        }
        Map<String, Integer> assets = field(identities, "assetIds");
        Map<IdIndex.PositionIdentity, Long> positions = field(identities, "positionKeys");
        String[] symbols = field(identities, "symbols");
        Map<String, Integer> instruments = new HashMap<>();
        for (int id = 0; id < symbols.length; id++)
            if (symbols[id] != null) instruments.put(symbols[id], id);
        return Map.of("assets", Map.copyOf(assets), "instruments", Map.copyOf(instruments),
                "positions", Map.copyOf(positions), "clients", Map.copyOf(clients));
    }

    private static void clients(ClientOrderIdRegistry table, Map<List<Object>, Long> target) {
        long[] keys = field(table, "keys");
        long[] users = field(table, "users");
        long[] references = field(table, "references");
        String[] names = field(table, "names");
        int rows = field(table, "nextRow");
        for (int row = 0; row < rows; row++)
            if (references[row] != 0) target.put(List.of(users[row], names[row]), keys[row]);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect identity field " + name, failure);
        }
    }
}
